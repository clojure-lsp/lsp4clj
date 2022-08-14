(ns lsp4clj.io-server-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [lsp4clj.io-server :as io-server]
   [lsp4clj.json-rpc :as json-rpc]
   [lsp4clj.json-rpc.messages :as messages]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h]))

(set! *warn-on-reflection* true)

(deftest server-test
  (let [server-input-stream (java.io.PipedInputStream.)
        server-output-stream (java.io.PipedOutputStream.)
        client-input-stream (java.io.PipedInputStream. server-output-stream)
        client-output-stream (java.io.PipedOutputStream. server-input-stream)
        server (io-server/server {:in server-input-stream :out server-output-stream})
        client-input-ch (json-rpc/input-stream->input-chan client-input-stream)
        client-output-ch (json-rpc/output-stream->output-chan client-output-stream)
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
