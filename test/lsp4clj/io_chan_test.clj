(ns lsp4clj.io-chan-test
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [lsp4clj.io-chan :as io-chan]
   [lsp4clj.test-helper :as h]))

(set! *warn-on-reflection* true)

(defn ^:private message-lines [arr]
  (string/join "\r\n" arr))

(defn mock-input-stream [^String input]
  (.getBytes input "utf-8"))

(deftest output-stream-should-camel-case-output
  (let [output-stream (java.io.ByteArrayOutputStream.)
        output-ch (io-chan/output-stream->output-chan output-stream)]
    (async/>!! output-ch {:parent-key {:child-key "child value"}})
    (async/close! output-ch)
    (Thread/sleep 200)
    (is (= (message-lines ["Content-Length: 40"
                           ""
                           "{\"parentKey\":{\"childKey\":\"child value\"}}"])
           (.toString output-stream "utf-8")))))

(deftest output-stream-should-not-camel-case-string-map-keys
  (let [output-stream (java.io.ByteArrayOutputStream.)
        output-ch (io-chan/output-stream->output-chan output-stream)]
    (async/>!! output-ch {:parent-key {"untouched-key" "child value"}})
    (async/close! output-ch)
    (Thread/sleep 200)
    (is (= (message-lines ["Content-Length: 45"
                           ""
                           "{\"parentKey\":{\"untouched-key\":\"child value\"}}"])
           (.toString output-stream "utf-8")))))

(deftest output-stream-should-set-content-length-to-number-of-bytes
  (let [output-stream (java.io.ByteArrayOutputStream.)
        output-ch (io-chan/output-stream->output-chan output-stream)]
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
        input-ch (io-chan/input-stream->input-chan input-stream)]
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
        input-ch (io-chan/input-stream->input-chan input-stream)]
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
        input-ch (io-chan/input-stream->input-chan input-stream)]
    (is (= {:key "apple"}
           (h/assert-take input-ch)))))

(deftest input-stream-should-return-parse-error
  (testing "when content length is wrong"
    (let [input-stream (mock-input-stream
                         (message-lines
                           ["Content-Length: 15"
                            ""
                            "{\"key\":\"äpfel\"}"]))
          input-ch (io-chan/input-stream->input-chan input-stream)]
      (is (= :parse-error (h/assert-take input-ch)))))
  (testing "when content length is malformed"
    (let [input-stream (mock-input-stream
                         (message-lines
                           ["Content-Length: nope"
                            ""
                            "{\"key\":\"apple\"}"]))
          input-ch (io-chan/input-stream->input-chan input-stream)]
      (is (= :parse-error (h/assert-take input-ch)))))
  (testing "when content type is malformed"
    (let [input-stream (mock-input-stream
                         (message-lines
                           ["Content-Length: 15"
                            "Content-Type: application/vscode-jsonrpc; charset=nope"
                            ""
                            "{\"key\":\"apple\"}"]))
          input-ch (io-chan/input-stream->input-chan input-stream)]
      (is (= :parse-error (h/assert-take input-ch))))))
