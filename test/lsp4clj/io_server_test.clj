(ns lsp4clj.io-server-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [lsp4clj.io-server :as io-server]
   [lsp4clj.json-rpc.messages :as messages]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h]))

(set! *warn-on-reflection* true)

(defn ^:private message-lines [arr]
  (string/join "\r\n" arr))

(defn mock-input-stream [input]
  (java.io.ByteArrayInputStream. (.getBytes input "utf-8")))

(deftest output-stream-should-camel-case-output
  (let [output-stream (java.io.ByteArrayOutputStream.)
        output-ch (io-server/output-stream->output-chan output-stream)]
    (async/>!! output-ch {:parent-key {:child-key "child value"}})
    (async/close! output-ch)
    (Thread/sleep 200)
    (is (= (message-lines ["Content-Length: 40"
                           ""
                           "{\"parentKey\":{\"childKey\":\"child value\"}}"])
           (.toString output-stream "utf-8")))))

(deftest output-stream-should-not-camel-case-string-map-keys
  (let [output-stream (java.io.ByteArrayOutputStream.)
        output-ch (io-server/output-stream->output-chan output-stream)]
    (async/>!! output-ch {:parent-key {"untouched-key" "child value"}})
    (async/close! output-ch)
    (Thread/sleep 200)
    (is (= (message-lines ["Content-Length: 45"
                           ""
                           "{\"parentKey\":{\"untouched-key\":\"child value\"}}"])
           (.toString output-stream "utf-8")))))

(deftest output-stream-should-set-content-length-to-number-of-bytes
  (let [output-stream (java.io.ByteArrayOutputStream.)
        output-ch (io-server/output-stream->output-chan output-stream)]
    (async/>!! output-ch {:key "apple"}) ;; 5 bytes
    (async/>!! output-ch {:key "äpfel"}) ;; 6 bytes
    (async/close! output-ch)
    (Thread/sleep 200)
    (is (= (message-lines ["Content-Length: 15"
                           ""
                           "{\"key\":\"apple\"}Content-Length: 16"
                           ""
                           "{\"key\":\"äpfel\"}"])
           (.toString output-stream "utf-8")))))

(deftest input-stream-should-kebab-case-input
  (let [input-stream (mock-input-stream
                       (message-lines
                         ["Content-Length: 40"
                          ""
                          "{\"parentKey\":{\"childKey\":\"child value\"}}"]))
        input-ch (io-server/input-stream->input-chan input-stream)]
    (is (= {:parent-key {:child-key "child value"}}
           (h/assert-take input-ch)))))

(deftest input-stream-should-read-number-of-bytes-from-content-length
  (let [input-stream (mock-input-stream
                       (message-lines
                         ["Content-Length: 15"
                          ""
                          "{\"key\":\"apple\"}Content-Length: 16"
                          ""
                          "{\"key\":\"äpfel\"}"]))
        input-ch (io-server/input-stream->input-chan input-stream)]
    (is (= {:key "apple"}
           (h/assert-take input-ch)))
    (is (= {:key "äpfel"}
           (h/assert-take input-ch)))))

(deftest input-stream-should-ignore-unexpected-headers
  (let [input-stream (mock-input-stream
                       (message-lines
                         ["Content-Length: 15"
                          "Referer: \"/\""
                          ""
                          "{\"key\":\"apple\"}"]))
        input-ch (io-server/input-stream->input-chan input-stream)]
    (is (= {:key "apple"}
           (h/assert-take input-ch)))))

(deftest input-stream-should-return-parse-error
  (testing "when content length is wrong"
    (let [input-stream (mock-input-stream
                         (message-lines
                           ["Content-Length: 15"
                            ""
                            "{\"key\":\"äpfel\"}"]))
          input-ch (io-server/input-stream->input-chan input-stream)]
      (is (= :parse-error (h/assert-take input-ch)))))
  (testing "when content length is malformed"
    (let [input-stream (mock-input-stream
                         (message-lines
                           ["Content-Length: nope"
                            ""
                            "{\"key\":\"apple\"}"]))
          input-ch (io-server/input-stream->input-chan input-stream)]
      (is (= :parse-error (h/assert-take input-ch)))))
  (testing "when content type is malformed"
    (let [input-stream (mock-input-stream
                         (message-lines
                           ["Content-Length: 15"
                            "Content-Type: application/vscode-jsonrpc; charset=nope"
                            ""
                            "{\"key\":\"apple\"}"]))
          input-ch (io-server/input-stream->input-chan input-stream)]
      (is (= :parse-error (h/assert-take input-ch))))))

;; Integration test

(deftest server-test
  (let [server-input-stream (java.io.PipedInputStream.)
        server-output-stream (java.io.PipedOutputStream.)
        client-input-stream (java.io.PipedInputStream. server-output-stream)
        client-output-stream (java.io.PipedOutputStream. server-input-stream)
        server (io-server/server {:in server-input-stream :out server-output-stream})
        client-input-ch (io-server/input-stream->input-chan client-input-stream)
        client-output-ch (io-server/output-stream->output-chan client-output-stream)
        join (server/start server nil)]
    ;; client initiates request
    (async/put! client-output-ch (messages/request 1 "foo" {}))
    ;; server responds
    (is (= {:jsonrpc "2.0",
            :id 1,
            :error {:code -32601,
                    :message "Method not found",
                    :data {:method "foo"}}}
           (h/assert-take client-input-ch)))
    ;; server initiates notification
    (server/send-notification server "bar" {:key "value"})
    ;; client receives
    (is (= {:jsonrpc "2.0",
            :method "bar",
            :params {:key "value"}}
           (h/assert-take client-input-ch)))
    (server/shutdown server)
    (is (= :done @join))))
