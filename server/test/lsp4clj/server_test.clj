(ns lsp4clj.server-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h]))

(deftest should-process-messages-received-before-start
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})]
    (async/put! input {:id 1 :method "foo" :params {}})
    (let [join (server/start server nil)]
      (h/assert-take output)
      (server/shutdown server)
      (server/exit server)
      @join)))

(deftest should-process-sent-messages-before-closing
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)]
    (async/put! input {:id 2
                       :method "bar"
                       :params {}})
    (server/shutdown server)
    (server/exit server)
    (h/assert-take output)
    @join))

(deftest should-close-when-asked-to
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)]
    (server/shutdown server)
    (server/exit server)
    (is (= :done @join))
    ;; output also closes
    (is (nil? (h/take-or-timeout output)))))

(deftest should-close-when-input-closes
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)]
    (async/close! input)
    (is (= :done @join))
    ;; output also closes
    (is (nil? (h/take-or-timeout output)))))

(deftest should-receive-responses
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)
        req (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output)]
    (async/put! input {:id (:id client-rcvd-msg)
                       :result {:processed true}})
    (is (= {:processed true} @req))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-respond-to-requests
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)]
    (async/put! input {:id 1 :method "foo" :params {}})
    (is (= 1 (:id (h/assert-take output))))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-reply-with-method-not-found-for-unexpected-messages
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)]
    (async/put! input {:id 1 :method "foo" :params {}})
    (is (= {:jsonrpc "2.0"
            :id 1
            :error {:code -32601, :message "Method not found", :data {:method "foo"}}}
           (h/assert-take output)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-cancel-if-no-response-received
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)
        req (server/send-request server "req" {:body "foo"})]
    ;; client receives message, but doesn't reply
    (h/assert-take output)
    (is (= :expected-timeout (server/deref-or-cancel req 100 :expected-timeout)))
    (is (= {:jsonrpc "2.0", :method "$/cancelRequest", :params {:id 1}}
           (h/assert-take output)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-not-cancel-after-client-replies
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)
        req (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output)]
    (async/put! input {:id (:id client-rcvd-msg)
                       :result {:processed true}})
    (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
    (h/assert-no-take output)
    (is (not (future-cancel req)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-send-only-one-cancellation
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1})
        join (server/start server nil)
        req (server/send-request server "req" {:body "foo"})]
    (h/assert-take output)
    (is (future-cancel req))
    (is (= "$/cancelRequest" (:method (h/assert-take output))))
    (is (not (future-cancel req)))
    (h/assert-no-take output)
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest request-should-behave-like-a-clojure-future
  (testing "before being handled"
    (let [input (async/chan 3)
          output (async/chan 3)
          server (server/chan-server {:output output
                                      :input input
                                      :parallelism 1})
          join (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (is (not (realized? req)))
      (is (not (future-done? req)))
      (is (not (future-cancelled? req)))
      (server/shutdown server)
      (server/exit server)
      @join))
  (testing "after response"
    (let [input (async/chan 3)
          output (async/chan 3)
          server (server/chan-server {:output output
                                      :input input
                                      :parallelism 1})
          join (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output)]
      (async/put! input {:id (:id client-rcvd-msg)
                         :result {:processed true}})
      (is (= {:processed true} (server/deref-or-cancel req 1000 :test-timeout)))
      (is (realized? req))
      (is (future-done? req))
      (is (not (future-cancelled? req)))
      (server/shutdown server)
      (server/exit server)
      @join))
  (testing "after cancellation"
    (let [input (async/chan 3)
          output (async/chan 3)
          server (server/chan-server {:output output
                                      :input input
                                      :parallelism 3})
          join (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (future-cancel req)
      (is (realized? req))
      (is (future-done? req))
      (is (future-cancelled? req))
      (server/shutdown server)
      (server/exit server)
      @join)))

(deftest request-should-behave-like-a-java-future
  (testing "before being handled"
    (let [input (async/chan 3)
          output (async/chan 3)
          server (server/chan-server {:output output
                                      :input input
                                      :parallelism 1})
          join (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (is (thrown? java.util.concurrent.TimeoutException
                   (.get req 500 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)
      (server/exit server)
      @join))
  (testing "after response"
    (let [input (async/chan 3)
          output (async/chan 3)
          server (server/chan-server {:output output
                                      :input input
                                      :parallelism 1})
          join (server/start server nil)
          req (server/send-request server "req" {:body "foo"})
          client-rcvd-msg (h/assert-take output)]
      (async/put! input {:id (:id client-rcvd-msg)
                         :result {:processed true}})
      (is (= {:processed true} (.get req 100 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)
      (server/exit server)
      @join))
  (testing "after cancellation"
    (let [input (async/chan 3)
          output (async/chan 3)
          server (server/chan-server {:output output
                                      :input input
                                      :parallelism 3})
          join (server/start server nil)
          req (server/send-request server "req" {:body "foo"})]
      (future-cancel req)
      (is (thrown? java.util.concurrent.CancellationException
                   (.get req 100 java.util.concurrent.TimeUnit/MILLISECONDS)))
      (server/shutdown server)
      (server/exit server)
      @join)))

(def fixed-clock
  (-> (java.time.LocalDateTime/of 2022 03 05 13 35 23 0)
      (.toInstant java.time.ZoneOffset/UTC)
      (java.time.Clock/fixed (java.time.ZoneId/systemDefault))))

(defn trace-str [lines]
  (string/join "\n" (into lines ["" "" ""])))

(deftest should-trace-received-notifications
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        join (server/start server nil)]
    (async/put! input {:method "foo" :params {:result "body"}})
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Received notification 'foo'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-trace-received-requests
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        join (server/start server nil)]
    (async/put! input {:id 1 :method "foo" :params {:result "body"}})
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Received request 'foo - (1)'"
                       "Params: {"
                       "  \"result\" : \"body\""
                       "}"])
           (h/assert-take trace-ch)))
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Sending response 'foo - (1)'. Processing request took 0ms"
                       "Result: {"
                       "  \"error\" : {"
                       "    \"code\" : -32601,"
                       "    \"message\" : \"Method not found\","
                       "    \"data\" : {"
                       "      \"method\" : \"foo\""
                       "    }"
                       "  }"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-trace-sent-requests
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        join (server/start server nil)
        _ (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output)]
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Sending request 'req - (1)'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (async/put! input {:id (:id client-rcvd-msg)
                       :result {:processed true}})
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Received response 'req - (1)' in 0ms."
                       "Result: {"
                       "  \"processed\" : true"
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-trace-sent-requests-with-error-responses
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        join (server/start server nil)
        _ (server/send-request server "req" {:body "foo"})
        client-rcvd-msg (h/assert-take output)]
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Sending request 'req - (1)'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (async/put! input {:id (:id client-rcvd-msg)
                       :error {:code 1234 :message "Something bad" :data {:body "foo"}}})
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Received response 'req - (1)' in 0ms. Request failed: Something bad (1234)."
                       "Error data: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-trace-unmatched-responses
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        join (server/start server nil)]
    (async/put! input {:id 100
                       :result {:processed true}})
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Received response for unmatched request:"
                       "Body: {"
                       "  \"id\" : 100,"
                       "  \"result\" : {"
                       "    \"processed\" : true"
                       "  }"
                       "}"
                       ])
           (h/assert-take trace-ch)))
    (server/shutdown server)
    (server/exit server)
    @join))

(deftest should-trace-sent-notifications
  (let [input (async/chan 3)
        output (async/chan 3)
        server (server/chan-server {:output output
                                    :input input
                                    :parallelism 1
                                    :trace? true
                                    :clock fixed-clock})
        trace-ch (:trace-ch server)
        join (server/start server nil)]
    (server/send-notification server "req" {:body "foo"})
    (is (= (trace-str ["[Trace - 2022-03-05T13:35:23Z] Sending notification 'req'"
                       "Params: {"
                       "  \"body\" : \"foo\""
                       "}"])
           (h/assert-take trace-ch)))
    (server/shutdown server)
    (server/exit server)
    @join))
