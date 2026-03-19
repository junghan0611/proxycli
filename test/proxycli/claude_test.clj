(ns proxycli.claude-test
  (:require [clojure.test :refer [deftest is testing]]
            [proxycli.claude :as claude]))

(deftest find-cli-test
  (testing "finds claude CLI"
    (is (string? (claude/find-cli)))))

(deftest models-test
  (testing "model list is not empty"
    (let [models (claude/models)]
      (is (seq models))
      (is (some #(= "claude-sonnet-4-6" %) models)))))

(deftest parse-stream-line-test
  (testing "parses assistant message"
    (let [line "{\"type\":\"assistant\",\"message\":{\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\",\"text\":\"Hello!\"}]}}"
          result (#'claude/parse-stream-line line)]
      (is (= :text (:type result)))
      (is (= "Hello!" (:text result)))))

  (testing "parses result message"
    (let [line "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"abc-123\",\"total_cost_usd\":0.01,\"duration_ms\":500,\"num_turns\":1,\"is_error\":false}"
          result (#'claude/parse-stream-line line)]
      (is (= :result (:type result)))
      (is (= "abc-123" (:session-id result)))))

  (testing "blank line returns nil"
    (is (nil? (#'claude/parse-stream-line "")))
    (is (nil? (#'claude/parse-stream-line nil))))

  (testing "unknown type returns nil (forward-compatible)"
    (let [line "{\"type\":\"unknown_future_type\",\"data\":{}}"
          result (#'claude/parse-stream-line line)]
      (is (nil? result)))))
