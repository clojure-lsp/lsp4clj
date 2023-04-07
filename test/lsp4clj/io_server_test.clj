(ns lsp4clj.io-server-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [lsp4clj.io-chan :as io-chan]
   [lsp4clj.io-server :as io-server]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.server :as server]
   [lsp4clj.test-helper :as h])
  (:import
   [java.io PipedInputStream PipedOutputStream]
   [java.net InetAddress ServerSocket Socket]))

(set! *warn-on-reflection* true)

(deftest should-communicate-over-io-streams
  (let [client-input-stream (PipedInputStream.)
        client-output-stream (PipedOutputStream.)
        server-input-stream (PipedInputStream. client-output-stream)
        server-output-stream (PipedOutputStream. client-input-stream)
        client-input-ch (io-chan/input-stream->input-chan client-input-stream)
        client-output-ch (io-chan/output-stream->output-chan client-output-stream)
        server (io-server/server {:in server-input-stream :out server-output-stream})
        join (server/start server nil)]
    ;; client initiates request
    (async/put! client-output-ch (lsp.requests/request 1 "foo" {}))
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

(deftest should-communicate-through-socket
  (let [addr (InetAddress/getLoopbackAddress)
        ;; ephermeral port, translated to real port via .getLocalPort
        port 0]
    (with-open [socket-server (ServerSocket. port 0 addr)
                socket-for-server (Socket. addr (.getLocalPort socket-server))
                socket-for-client (.accept socket-server)]
      (let [client-input-ch (io-chan/input-stream->input-chan socket-for-client)
            client-output-ch (io-chan/output-stream->output-chan socket-for-client)
            server (io-server/server {:in socket-for-server
                                      :out socket-for-server})
            join (server/start server nil)]
        (async/put! client-output-ch (lsp.requests/request 1 "foo" {}))
        (is (= {:jsonrpc "2.0",
                :id 1,
                :error {:code -32601,
                        :message "Method not found",
                        :data {:method "foo"}}}
               (h/assert-take client-input-ch)))
        (server/send-notification server "bar" {:key "value"})
        (is (= {:jsonrpc "2.0",
                :method "bar",
                :params {:key "value"}}
               (h/assert-take client-input-ch)))
        (server/shutdown server)
        (is (= :done @join))))))
