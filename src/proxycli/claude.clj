(ns proxycli.claude
  "Claude CLI subprocess execution and stream-json parsing.
   Replaces Python SDK (4,828 lines) with ~160 lines."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

;; ── CLI path lookup ────────────────────────────────────────────

(defn find-cli
  "Find claude CLI binary path."
  []
  (or (System/getenv "CLAUDE_CLI_PATH")
      (let [^Process which (try
                            (-> (ProcessBuilder. ["which" "claude"])
                                (.redirectErrorStream true)
                                .start)
                            (catch Exception _ nil))]
        (when which
          (let [path (str/trim (slurp (.getInputStream which)))]
            (when (and (.waitFor which) (not (str/blank? path)))
              path))))
      "claude"))

;; ── Configuration ──────────────────────────────────────────────

(def ^:private default-models
  ["claude-sonnet-4-6" "claude-opus-4-6"
   "claude-sonnet-4-5-20250929" "claude-haiku-4-5-20251001"
   "claude-opus-4-5-20251101" "claude-opus-4-5-20250929"
   "claude-opus-4-1-20250805" "claude-opus-4-20250514"
   "claude-sonnet-4-20250514"])

(def core-tools
  "Core tools for gptel use case."
  ["Read" "Write" "Edit" "Bash" "Glob" "Grep" "WebSearch" "WebFetch"])

(defn- env-truthy? [k]
  (contains? #{"1" "true" "yes"} (some-> (System/getenv k) str/lower-case)))

(def ^:private empty-mcp-path
  "Empty MCP config file path (created once)."
  (memoize
   (fn []
     (let [f (java.io.File/createTempFile "proxycli-empty-mcp" ".json")]
       (.deleteOnExit f)
       (spit f "{\"mcpServers\": {}}")
       (.getAbsolutePath f)))))

;; ── Skill loading ──────────────────────────────────────────────

(defn- load-skill
  "Read ~/.claude/skills/<name>/SKILL.md content."
  [skill-name]
  (let [path (str (System/getProperty "user.home")
                  "/.claude/skills/" skill-name "/SKILL.md")]
    (when (.exists (io/file path))
      (slurp path))))

(defn- build-skills-prompt
  "Build system prompt fragment from PROXYCLI_SKILLS env var.
   e.g. PROXYCLI_SKILLS=botlog,denotecli,bibcli"
  []
  (when-let [skills-str (System/getenv "PROXYCLI_SKILLS")]
    (let [names   (str/split skills-str #"[,;:\s]+")
          loaded  (keep (fn [n]
                          (when-let [content (load-skill n)]
                            (str "## Skill: " n "\n" content)))
                        names)]
      (when (seq loaded)
        (str "# Available Skills\n\n" (str/join "\n\n" loaded))))))

;; ── Time info ──────────────────────────────────────────────────

(defn- current-time-prompt
  "Current KST time info for Denote ID generation."
  []
  (let [kst    (java.time.ZonedDateTime/now (java.time.ZoneId/of "Asia/Seoul"))
        fmt-ts (.format kst (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss"))
        fmt-dt (.format kst (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd EEE HH:mm"))]
    (str "Current time (KST): " fmt-dt " | Denote timestamp: " fmt-ts)))

;; ── CLI command builder ────────────────────────────────────────

(defn- build-command
  "Build Claude CLI command.
   Clean mode: blocks all MCP/plugins/skills → 13.5K tokens.
   Injects only skills declared in PROXYCLI_SKILLS via system prompt."
  [{:keys [prompt model system-prompt max-turns
           allowed-tools permission-mode]}]
  (let [cli            (find-cli)
        minimal-tools? (env-truthy? "CLAUDE_MINIMAL_TOOLS")
        ;; Assemble system prompt: time + skills + user-specified
        parts          (filterv some?
                                [(current-time-prompt)
                                 (build-skills-prompt)
                                 system-prompt])
        full-sys       (str/join "\n\n" parts)]
    (cond-> [cli "--output-format" "stream-json" "--verbose"
             "--max-turns" (str (or max-turns 10))
             "--print" prompt
             ;; Clean mode: block MCP/plugins/slash commands
             "--strict-mcp-config" "--mcp-config" (empty-mcp-path)
             "--disable-slash-commands" "--setting-sources" ""]
      ;; Minimal tools
      (and minimal-tools?
           (not allowed-tools))
                       (into ["--tools" (str/join "," core-tools)])
      ;; Time + skills injection
      true             (into ["--append-system-prompt" full-sys])
      ;; Explicit options
      model            (into ["--model" model])
      allowed-tools    (into ["--allowedTools" (str/join "," allowed-tools)])
      permission-mode  (into ["--permission-mode" permission-mode]))))

;; ── stream-json parsing ────────────────────────────────────────

(defn- parse-text-blocks [content]
  (when (sequential? content)
    (->> content
         (keep (fn [block]
                 (when (= "text" (:type block))
                   (:text block))))
         (str/join ""))))

(defn- parse-stream-line
  "Parse a stream-json line. Returns nil for unsupported types (forward-compatible)."
  [^String line]
  (when-not (str/blank? line)
    (try
      (let [msg (json/read-str line :key-fn keyword)]
        (case (:type msg)
          "assistant"
          (let [text (parse-text-blocks (get-in msg [:message :content]))]
            (when (and text (not (str/blank? text)))
              {:type :text :text text :model (get-in msg [:message :model])}))
          "tool_use"    {:type :tool :name (:name msg)}
          "tool_result" {:type :tool-result
                         :text (let [c (:content msg)]
                                 (cond
                                   (string? c) c
                                   (sequential? c)
                                   (->> c (keep #(when (= "text" (:type %)) (:text %))) (str/join ""))
                                   :else nil))}
          "result"      {:type :result
                         :session-id (:session_id msg)
                         :cost       (:total_cost_usd msg)
                         :duration   (:duration_ms msg)
                         :num-turns  (:num_turns msg)
                         :is-error   (:is_error msg)
                         :result-text (:result msg)}
          "system"      {:type :system :subtype (:subtype msg) :data msg}
          nil))
      (catch Exception _ nil))))

;; ── Query execution ────────────────────────────────────────────

(defn query!
  "Execute Claude CLI and return stream-json messages.
   Calls callback-fn for each message when provided (streaming)."
  [{:keys [cwd callback-fn] :as opts}]
  (let [cmd  (build-command opts)
        pb   (doto (ProcessBuilder. ^java.util.List cmd)
               (.redirectErrorStream false))
        _    (when cwd (.directory pb (io/file cwd)))
        proc (.start pb)]
    (.close (.getOutputStream proc))
    (let [reader (BufferedReader. (InputStreamReader. (.getInputStream proc) "UTF-8"))]
      (try
        (loop [messages []]
          (if-let [line (.readLine reader)]
            (let [parsed (parse-stream-line line)]
              (when (and parsed callback-fn) (callback-fn parsed))
              (recur (if parsed (conj messages parsed) messages)))
            (do (.waitFor proc) messages)))
        (finally
          (.close reader)
          (.destroy proc))))))

(defn models [] default-models)
