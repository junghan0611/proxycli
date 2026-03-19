(ns proxycli.server
  "Ring HTTP server — OpenAI-compatible API endpoints.
   Two endpoints that gptel actually uses:
     POST /v1/chat/completions  (SSE streaming)
     GET  /v1/models"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [proxycli.claude :as claude]))

;; ── Message conversion ─────────────────────────────────────────

(defn- messages->prompt
  "Convert OpenAI messages array to Claude prompt string and system-prompt."
  [messages]
  (let [system-msgs  (filter #(= "system" (:role %)) messages)
        conv-msgs    (remove #(= "system" (:role %)) messages)
        system-prompt (when (seq system-msgs)
                        (:content (last system-msgs)))
        prompt-parts (map (fn [{:keys [role content]}]
                            (case role
                              "user"      (str "Human: " content)
                              "assistant" (str "Assistant: " content)
                              content))
                          conv-msgs)
        prompt       (str/join "\n\n" prompt-parts)]
    [prompt system-prompt]))

;; ── SSE streaming ──────────────────────────────────────────────

(defn- sse-chunk
  "Format as OpenAI chat.completion.chunk SSE."
  [request-id model delta & {:keys [finish-reason]}]
  (let [chunk {:id      request-id
               :object  "chat.completion.chunk"
               :created (quot (System/currentTimeMillis) 1000)
               :model   model
               :choices [{:index         0
                          :delta         delta
                          :finish_reason finish-reason}]}]
    (str "data: " (json/write-str chunk) "\n\n")))

(defn- generate-request-id []
  (str "chatcmpl-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

;; ── Claude options builder ─────────────────────────────────────

(defn- build-claude-opts
  "Build Claude CLI options from request parameters."
  [prompt system-prompt model enable-tools cwd]
  (cond-> {:prompt  prompt
           :model   model
           :cwd     cwd}
    system-prompt (assoc :system-prompt system-prompt)
    enable-tools  (assoc :max-turns       10
                         :allowed-tools   claude/core-tools
                         :permission-mode "bypassPermissions")
    (not enable-tools) (assoc :max-turns 1)))

;; ── Logging ────────────────────────────────────────────────────

(defn- log [& args]
  (let [ts (-> (java.time.LocalDateTime/now)
               (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")))]
    (println (str ts " " (str/join " " args)))))

;; ── Handlers ───────────────────────────────────────────────────

(defn- handle-chat-completions
  "POST /v1/chat/completions — SSE streaming & non-streaming response."
  [body cwd default-model]
  (let [{:keys [messages stream]
         :or   {stream true}} body
        model        (or (:model body) default-model)
        enable-tools (:enable_tools body false)
        [prompt system-prompt] (messages->prompt messages)
        request-id   (generate-request-id)
        claude-opts  (build-claude-opts prompt system-prompt model enable-tools cwd)
        prompt-preview (let [p (str/replace prompt #"\n" " ")]
                         (if (> (count p) 60) (str (subs p 0 60) "...") p))
        start-ms     (System/currentTimeMillis)]

    (log "")
    (log "════════════════════════════════════════")
    (log "🔄" model "|" (if stream "stream" "sync")
         (when enable-tools " | 🔧 tools"))
    (log "   " prompt-preview)

    (if stream
      ;; ── Streaming response ──
      (let [out (java.io.PipedOutputStream.)
            in  (java.io.PipedInputStream. out 65536)]
        (future
          (try
            (let [^java.io.Writer writer (java.io.OutputStreamWriter. out "UTF-8")
                  first-ms (atom nil)
                  w! (fn [^String s] (.write writer s) (.flush writer))]
              ;; Role chunk
              (w! (sse-chunk request-id model {:role "assistant" :content ""}))

              ;; Execute Claude + stream
              (claude/query!
               (assoc claude-opts
                      :callback-fn
                      (fn [{:keys [type text name]}]
                        (when (and (= type :text) (not (str/blank? text)))
                          (when-not @first-ms
                            (reset! first-ms (System/currentTimeMillis))
                            (log "   ⏱️ first token:" (str (- @first-ms start-ms) "ms")))
                          (w! (sse-chunk request-id model {:content text})))
                        (when (= type :tool)
                          (log "   🔧" name))
                        (when (and (= type :tool-result) text (not (str/blank? text)))
                          (w! (sse-chunk request-id model
                                         {:content (str "\n```\n" text "\n```\n")}))))))

              ;; Final chunk
              (w! (sse-chunk request-id model {} :finish-reason "stop"))
              (w! "data: [DONE]\n\n")
              (let [elapsed (- (System/currentTimeMillis) start-ms)]
                (log "✅" (str elapsed "ms")
                     "════════════════════════════════════════")))
            (catch Exception e
              (log "❌" (.getMessage e))
              (let [^java.io.Writer w (java.io.OutputStreamWriter. out "UTF-8")]
                (.write w ^String (str "data: " (json/write-str {:error {:message (.getMessage e)}}) "\n\n"))
                (.flush w)))
            (finally
              (.close out))))
        {:status  200
         :headers {"Content-Type"  "text/event-stream"
                   "Cache-Control" "no-cache"
                   "Connection"    "keep-alive"}
         :body    in})

      ;; ── Non-streaming response ──
      (let [messages-out (claude/query! claude-opts)
            text-parts   (->> messages-out
                              (filter #(= :text (:type %)))
                              (map :text))
            result-text  (or (->> messages-out
                                  (filter #(= :result (:type %)))
                                  first
                                  :result-text)
                             (str/join "" text-parts)
                             "")
            elapsed      (- (System/currentTimeMillis) start-ms)]
        (log "✅" (str elapsed "ms") "|" (str (count result-text) " chars")
             "════════════════════════════════════════")
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/write-str
                   {:id      request-id
                    :object  "chat.completion"
                    :created (quot (System/currentTimeMillis) 1000)
                    :model   model
                    :choices [{:index         0
                               :message       {:role "assistant" :content result-text}
                               :finish_reason "stop"}]
                    :usage   {:prompt_tokens     (max 1 (quot (count prompt) 4))
                              :completion_tokens (max 1 (quot (count result-text) 4))
                              :total_tokens      (max 2 (quot (+ (count prompt)
                                                                  (count result-text)) 4))}})}))))

(defn- handle-models []
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str
             {:object "list"
              :data   (mapv (fn [id] {:id id :object "model" :owned_by "anthropic"})
                            (claude/models))})})

(defn- handle-health []
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str {:status "healthy" :service "proxycli"})})

;; ── Ring handler ───────────────────────────────────────────────

(defn- read-body [request]
  (when-let [body (:body request)]
    (json/read-str (slurp body) :key-fn keyword)))

(defn make-handler
  "Create Ring handler."
  [cwd default-model]
  (fn [request]
    (let [method (:request-method request)
          uri    (:uri request)
          response
          (cond
            (= method :options)
            {:status 204 :headers {} :body ""}

            (and (= method :post) (= uri "/v1/chat/completions"))
            (handle-chat-completions (read-body request) cwd default-model)

            (and (= method :get) (= uri "/v1/models"))
            (handle-models)

            (and (= method :get) (= uri "/health"))
            (handle-health)

            :else
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error {:message "Not found"}})})

          cors-headers {"Access-Control-Allow-Origin"  "*"
                        "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                        "Access-Control-Allow-Headers" "*"}]
      (update response :headers merge cors-headers))))

;; ── Server startup ─────────────────────────────────────────────

(defn start!
  "Start HTTP server."
  [port cwd default-model]
  (let [handler (make-handler cwd default-model)]
    (println (str "🚀 proxycli listening on http://localhost:" port))
    (println "   POST /v1/chat/completions")
    (println "   GET  /v1/models")
    (println "   GET  /health")
    (println "   Press Ctrl+C to stop")
    (jetty/run-jetty handler {:port port :join? true})))
