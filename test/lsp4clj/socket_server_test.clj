(ns lsp4clj.socket-server-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [lsp4clj.io-chan :as io-chan]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.server :as server]
   [lsp4clj.socket-server :as socket-server]
   [lsp4clj.test-helper :as h])
  (:import
   [java.net ServerSocket Socket]))

(set! *warn-on-reflection* true)

(deftest should-communicate-through-socket
  (let [;; NOTE: In real-life, you'd replace the following two lines with
        ;; (socket-server/server {:port 51234}). In tests, setup is split into
        ;; two steps for two reasons.
        ;; 1. We have to ensure the socket is bound before the client starts, to
        ;;    avoid flakiness. If we had just `(future (socket-server/server
        ;;    {:port 51234}))` occasionally the client would start first,
        ;;    leading to a ConnectionRefused exception. The only other way to
        ;;    avoid this flakiness is to sleep briefly, or to poll for the
        ;;    socket to be isBound.
        ;; 2. We want to use an ephemeral port, which is what `{:port 0}` is
        ;;    about. But the port we are assigned is exposed only through the
        ;;    socket-data.
        {:keys [^ServerSocket socket], :as socket-data} (socket-server/bind-socket {:port 0})
        server* (future (socket-server/server {} socket-data))]
    (try
      (with-open [client (Socket. (.getInetAddress socket) (.getLocalPort socket))]
        (let [client-input-ch (io-chan/input-stream->input-chan client)
              client-output-ch (io-chan/output-stream->output-chan client)
              server @server*
              join (server/start server nil)]
          (try
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
            (finally
              (async/close! client-output-ch)
              (async/close! client-input-ch)))
          (is (= :done @join))))
      (finally (server/shutdown @server*)))))
