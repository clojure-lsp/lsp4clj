(ns lsp4clj.server-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [lsp4clj.json-rpc.messages :as messages]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h]))

(deftest should-process-messages-received-before-start
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (async/put! input-ch (messages/request 1 "foo" {}))
    (server/start server nil)
    (h/assert-take output-ch)
    (server/shutdown server)))

(deftest should-process-sent-messages-before-closing
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (async/put! input-ch (messages/request 2 "bar" {}))
    (server/shutdown server)
    (h/assert-take output-ch)))

(deftest should-close-when-asked-to
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (is (= :done (server/shutdown server)))
    ;; output-ch also closes
    (is (nil? (h/take-or-timeout output-ch)))))

(deftest should-close-when-input-ch-closes
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        join (server/start server nil)]
    (async/close! input-ch)
    (is (= :done (deref join 100 :timed-out)))
    ;; output-ch also closes
    (is (nil? (h/take-or-timeout output-ch)))))

(deftest should-receive-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (async/put! input-ch (messages/response (:id client-rcvd-msg) {:processed true}))
    (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
    (server/shutdown server)))

(deftest should-respond-to-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (async/put! input-ch (messages/request 1 "foo" {}))
    (is (= 1 (:id (h/assert-take output-ch))))
    (server/shutdown server)))

(deftest should-reply-with-method-not-found-for-unexpected-messages
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})]
    (server/start server nil)
    (async/put! input-ch (messages/request 1 "foo" {}))
    (is (= {:jsonrpc "2.0"
            :id 1
            :error {:code -32601, :message "Method not found", :data {:method "foo"}}}
           (h/assert-take output-ch)))
    (server/shutdown server)))

(deftest should-cancel-if-no-response-received
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})]
    ;; client receives message, but doesn't reply
    (h/assert-take output-ch)
    (is (= :expected-timeout (server/deref-or-cancel req 100 :expected-timeout)))
    (is (= {:jsonrpc "2.0", :method "$/cancelRequest", :params {:id 1}}
           (h/assert-take output-ch)))
    (server/shutdown server)))

(deftest should-not-cancel-after-client-replies
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (async/put! input-ch (messages/response (:id client-rcvd-msg) {:processed true}))
    (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
    (h/assert-no-take output-ch)
    (is (not (future-cancel req)))
    (server/shutdown server)))

(deftest should-send-only-one-cancellation
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        _ (server/start server nil)
        req (server/send-request server "req" {:body "foo"})]
    (h/assert-take output-ch)
    (is (future-cancel req))
    (is (= "$/cancelRequest" (:method (h/assert-take output-ch))))
    (is (not (future-cancel req)))
    (h/assert-no-take output-ch)
    (server/shutdown server)))

(deftest request-should-behave-like-a-clojure-future
  (testing "before being handled"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (is (not (realized? req)))
      (is (not (future-done? req)))
      (is (not (future-cancelled? req)))
      (server/shutdown server)))
  (testing "after response"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output-ch)]
      (async/put! input-ch (messages/response (:id client-rcvd-msg) {:processed true}))
      (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
      (is (realized? req))
      (is (future-done? req))
      (is (not (future-cancelled? req)))
      (server/shutdown server)))
  (testing "after cancellation"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (future-cancel req)
      (is (realized? req))
      (is (future-done? req))
      (is (future-cancelled? req))
      (server/shutdown server))))

(deftest request-should-behave-like-a-java-future
  (testing "before being handled"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (is (thrown? java.util.concurrent.TimeoutException
                   (.get req 500 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)))
  (testing "after response"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output-ch)]
      (async/put! input-ch (messages/response (:id client-rcvd-msg) {:processed true}))
      (is (= {:processed true} (.get req 100 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)))
  (testing "after cancellation"
    (let [input-ch (async/chan 3)
          output-ch (async/chan 3)
          server (server/chan-server {:output-ch output-ch
                                      :input-ch input-ch})
          _ (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (future-cancel req)
      (is (thrown? java.util.concurrent.CancellationException
                   (.get req 100 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server))))

(def fixed-clock
  (-> (java.time.LocalDateTime/of 2022 03 05 13 35 23 0)
      (.toInstant java.time.ZoneOffset/UTC)
      (java.time.Clock/fixed (java.time.ZoneId/systemDefault))))

(defn trace-log [lines]
  [:debug (string/join "\n" (into lines ["" "" ""]))])

(deftest should-trace-received-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (messages/request "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-received-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (messages/request 1 "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received request 'foo - (1)'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending response 'foo - (1)'. Request took 0ms. Request failed: Method not found (-32601)."
                       "Error data: {"
                       "  \"method\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-sent-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        _ (server/start server nil)
        _ (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending request 'req - (1)'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (async/put! input-ch (messages/response (:id client-rcvd-msg) {:processed true}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received response 'req - (1)'. Request took 0ms."
                       "Result: {"
                       "  \"processed\" : true"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-sent-requests-with-error-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        _ (server/start server nil)
        _ (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output-ch)]
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending request 'req - (1)'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (async/put! input-ch
                (messages/response (:id client-rcvd-msg)
                                   {:error {:code 1234 :message "Something bad" :data {:body "foo"}}}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received response 'req - (1)'. Request took 0ms. Request failed: Something bad (1234)."
                       "Error data: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-unmatched-responses
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (async/put! input-ch (messages/response 100 {:processed true}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received response for unmatched request:"
                       "Body: {"
                       "  \"jsonrpc\" : \"2.0\","
                       "  \"id\" : 100,"
                       "  \"result\" : {"
                       "    \"processed\" : true"
                       "  }"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-trace-sent-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)]
    (server/start server nil)
    (server/send-notification server "req" {:body "foo"})
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Sending notification 'req'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)))

(deftest should-log-unexpected-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch (messages/request 1 "foo" {}))
    (is (= [:warn "received unexpected request" "foo"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-log-unexpected-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch (messages/request "foo" {}))
    (is (= [:warn "received unexpected notification" "foo"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-log-parse-errors
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch :parse-error)
    (is (= [:error "Error reading message: Parse error (-32700)"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-log-and-respond-to-internal-errors-during-requests
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (with-redefs [server/receive-request (fn [& _args]
                                           (throw (ex-info "internal error" {:redef :data})))]
      (async/put! input-ch (messages/request 1 "foo" {}))
      (is (= {:jsonrpc "2.0",
              :id 1,
              :error {:code -32603,
                      :message "Internal error",
                      :data {:id 1, :method "foo"}}}
             (h/assert-take output-ch))))
    (let [[level e message] (h/assert-take log-ch)]
      (is (= :error level))
      (is (= {:redef :data} (ex-data e)))
      (is (= "Error receiving message: Internal error (-32603)\n{:id 1, :method \"foo\"}" message)))
    (server/shutdown server)))

(deftest should-log-internal-errors-during-notifications
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (with-redefs [server/receive-notification (fn [& _args]
                                                (throw (ex-info "internal error" {:redef :data})))]
      (async/put! input-ch (messages/request "foo" {}))
      (h/assert-no-take output-ch))
    (let [[level e message] (h/assert-take log-ch)]
      (is (= :error level))
      (is (= {:redef :data} (ex-data e)))
      (is (= "Error receiving message: Internal error (-32603)\n{:method \"foo\"}" message)))
    (server/shutdown server)))

(deftest should-log-malformed-messages
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch})
        log-ch (:log-ch server)]
    (server/start server nil)
    (async/put! input-ch {:jsonrpc "1.0"})
    (is (= [:error "Error reading message: Invalid Request (-32600)"]
           (h/assert-take log-ch)))
    (server/shutdown server)))

(deftest should-merge-logs-and-traces-if-requested
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)
        merged-ch (async/chan (async/sliding-buffer 20))
        server (server/chan-server {:output-ch output-ch
                                    :input-ch input-ch
                                    :log-ch merged-ch
                                    :trace-ch merged-ch
                                    :clock fixed-clock})]
    (server/start server nil)
    (async/put! input-ch (messages/request "foo" {:result "body"}))
    (is (= (trace-log ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take merged-ch)))
    (is (= [:warn "received unexpected notification" "foo"]
           (h/assert-take merged-ch)))
    (server/shutdown server)))
