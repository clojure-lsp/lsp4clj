(ns lsp4clj.socket-server
  "DEPRECATED: Start a socket and listen on it.

  This namespace is deprecated and is scheduled for deletion. See the README for
  recommendations on how to communicate with an LSP client over a socket.

  This namespace was implemented based on a misconception. The LSP spec says
  servers should accept a `--port` CLI argument for socket-based communication.
  We assumed that the server was expected to start a socket server on that port,
  then wait for the client to connect and start sending messages. However, this
  is not how clients actually work. Instead, the _client_ opens the socket
  server, and tells the LSP server which port to connect to. That is, the LSP
  client acts as a socket server and the LSP server acts as a socket client.

  This namespace, which largely deals with starting a socket server, is
  therefore unusable in an LSP server. If the LSP server did try to open a
  server on the provided `--port`, it should always fail, because the client
  should already be listening on that port."
  {:deprecated "1.7.4"}
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
                              :in conn
                              :out conn
                              :on-close on-close)))))
