(ns proxycli.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [proxycli.server :as server]))

(deftest messages->prompt-test
  (testing "converts user message"
    (let [[prompt sys] (#'server/messages->prompt
                        [{:role "user" :content "Hello"}])]
      (is (= "Human: Hello" prompt))
      (is (nil? sys))))

  (testing "converts system + user messages"
    (let [[prompt sys] (#'server/messages->prompt
                        [{:role "system" :content "You are helpful"}
                         {:role "user" :content "Hi"}])]
      (is (= "Human: Hi" prompt))
      (is (= "You are helpful" sys))))

  (testing "converts multi-turn conversation"
    (let [[prompt _] (#'server/messages->prompt
                      [{:role "user" :content "Hello"}
                       {:role "assistant" :content "Hi there"}
                       {:role "user" :content "How are you?"}])]
      (is (re-find #"Human: Hello" prompt))
      (is (re-find #"Assistant: Hi there" prompt))
      (is (re-find #"Human: How are you\?" prompt)))))

(deftest handler-models-test
  (testing "GET /v1/models response"
    (let [handler (server/make-handler "/tmp" "claude-sonnet-4-6")
          resp    (handler {:request-method :get :uri "/v1/models"})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 200 (:status resp)))
      (is (= "list" (:object body)))
      (is (seq (:data body)))
      (is (some #(= "claude-sonnet-4-6" (:id %)) (:data body))))))

(deftest handler-health-test
  (testing "GET /health response"
    (let [handler (server/make-handler "/tmp" "claude-sonnet-4-6")
          resp    (handler {:request-method :get :uri "/health"})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 200 (:status resp)))
      (is (= "healthy" (:status body))))))

(deftest handler-404-test
  (testing "unknown path returns 404"
    (let [handler (server/make-handler "/tmp" "claude-sonnet-4-6")
          resp    (handler {:request-method :get :uri "/nonexistent"})]
      (is (= 404 (:status resp))))))

(deftest cors-test
  (testing "CORS headers are present"
    (let [handler (server/make-handler "/tmp" "claude-sonnet-4-6")
          resp    (handler {:request-method :get :uri "/health"})]
      (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"]))))))
