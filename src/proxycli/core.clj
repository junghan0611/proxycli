(ns proxycli.core
  "proxycli — CLI-to-OpenAI API proxy.
   Wraps Claude Code CLI as OpenAI-compatible API endpoint."
  (:gen-class)
  (:require [proxycli.claude :as claude]
            [proxycli.server :as server]
            [clojure.string :as str]))

(defn- env-truthy? [k]
  (contains? #{"1" "true" "yes"} (some-> (System/getenv k) str/lower-case)))

(defn -main [& args]
  (let [port  (or (some-> (System/getenv "PORT") parse-long)
                  (some-> (first args) parse-long)
                  28000)
        cwd   (or (System/getenv "CLAUDE_CWD")
                  (str (System/getProperty "user.home") "/org"))
        model (or (System/getenv "DEFAULT_MODEL") "claude-sonnet-4-6")
        independent? (env-truthy? "CLAUDE_INDEPENDENT_MODE")
        minimal?     (env-truthy? "CLAUDE_MINIMAL_TOOLS")]
    (println "")
    (println "==================================================")
    (println "  proxycli — CLI-to-OpenAI API Proxy (Clojure)")
    (println (str "  Mode:  " (if independent?
                                 "⚡ Independent (MCP off)"
                                 "🔗 Full (MCP on)")))
    (println (str "  Tools: " (if minimal?
                                 "🔧 Minimal (8 tools)"
                                 "🛠️  Full (all tools)")))
    (println (str "  CWD:   " cwd))
    (println (str "  Model: " model))
    (println (str "  CLI:   " (claude/find-cli)))
    (println "==================================================")
    (println "")
    (server/start! port cwd model)))
