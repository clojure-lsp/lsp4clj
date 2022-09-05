(ns lsp4clj.socket-server
  (:require
   [lsp4clj.io-server :as io-server])
  (:import
   [java.net InetAddress ServerSocket Socket]))

(set! *warn-on-reflection* true)

(defn bind-socket
  "Binds to the `:address` (string, loopback by default) and `:port` (integer,
  required).

  Returns a map containing the `:socket` (a ServerSocket) and a `:connection`, a
  future which will resolve (to a Socket) when a client connects.

  The caller is responsible for closing the socket and the connection."
  [{:keys [address port]}]
  (let [address (InetAddress/getByName address) ;; nil address returns loopback
        socket (ServerSocket. port 0 address)] ;; bind to the port
    {:socket socket
     :connection (future (.accept socket))}))

(defn server
  "Start a socket server, given the specified opts:
    `:address` Host or address, string, defaults to loopback address
    `:port` Port, string or integer, required

  Starts listening on the socket, blocks until a client establishes a
  connection, then returns a chan-server which communicates over the socket."
  ([{:keys [address port] :as opts}]
   (let [port (if (string? port) (Long/valueOf ^String port) port)]
     (server opts (bind-socket {:address address
                                :port port}))))
  ([opts {:keys [^ServerSocket socket connection]}] ;; this arity is mostly for tests
   (let [^Socket conn @connection
         ;; NOTE: When the chan-server is shutdown, the input-ch and output-ch
         ;; will also close. This will close the in and out streams, and
         ;; eventually the conn too, so re-closing the conn is probably
         ;; unnecessary, but doesn't seem to hurt. Regardless, we should close
         ;; the socket too.
         on-close #(do
                     (.close conn)
                     (.close socket))]
     (io-server/server (assoc opts
                              :in (.getInputStream conn)
                              :out (.getOutputStream conn)
                              :on-close on-close)))))
