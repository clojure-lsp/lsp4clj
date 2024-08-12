# lsp4clj

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) base for developing any LSP implementation in Clojure.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-lsp/lsp4clj.svg)](https://clojars.org/com.github.clojure-lsp/lsp4clj)

lsp4clj reads and writes from io streams, parsing JSON-RPC according to the LSP spec. It provides tools to allow server implementors to receive, process, and respond to any of the methods defined in the LSP spec, and to send their own requests and notifications to clients.

## Usage

### Create a server

To initialize a server that will read from stdin and write to stdout:

```clojure
(lsp4clj.io-server/stdio-server
  {:request-handler receive-request
   :notification-handler receive-notification})
```

The returned server will have a core.async `:log-ch`, from which you can read server logs (vectors beginning with a log level).

```clojure
(async/go-loop []
  (when-let [[level & args] (async/<! (:log-ch server))]
    (apply logger/log level args)
    (recur)))
```

### Receive messages

To receive messages from a client, lsp4clj defines a pair of handlers: the `:request-handler`
and the `:notification-handler`, which should be passed to the server constructor.

These handlers receive 3 arguments, the method name, a "context", and the `params` of the [JSON-RPC request or notification object](https://www.jsonrpc.org/specification#request_object). The keys of the params will have been converted (recursively) to kebab-case keywords. Read on for an explanation of what a "context" is and how to set it.

When a message is not understood by the server, these handlers should return the value `:lsp4clj.server/method-not-found`.  For requests, lsp4clj will then report an appropriate error message to the client.

lsp4clj provides two multimethods as default handlers, `lsp4clj.server/receive-notification` and `lsp4clj.server/receive-request`, that dispatch on the method name (as defined by the LSP spec) of an incoming JSON-RPC message.  Instead of passing custom `:request-handler` and `:notification-handler` options when creating the server, implementors can create `defmethod`s for the messages they want to process. (Other methods will be logged and responded to with a generic "Method not found" response.)

Note that the use of these multimethods is deprecated and they will be removed in a future release of lsp4clj.  New code should pass their own handlers instead, potentially defining their own multimethod.

```clojure
(defmulti receive-request (fn [method _context _params] method))
(defmulti receive-notification (fn [method _context _params] method))

;; a notification; return value is ignored
(defmethod receive-notification "textDocument/didOpen"
  [_ context {:keys [text-document]}]
  (handler/did-open context (:uri text-document) (:text text-document))
  
;; a request; return value is converted to a response
(defmethod receive-request "textDocument/definition"
  [_ context params]
  (->> params
       (handler/definition context)
       (conform-or-log ::coercer/location)))
```

The return value of requests will be converted to camelCase json and returned to the client. If the return value looks like `{:error ...}`, it is assumed to indicate an error response, and the `...` part will be set as the `error` of a [JSON-RPC error object](https://www.jsonrpc.org/specification#error_object). It is up to you to conform the `...` object (by giving it a `code`, `message`, and `data`.) Otherwise, the entire return value will be set as the `result` of a [JSON-RPC response object](https://www.jsonrpc.org/specification#response_object). (Message ids are handled internally by lsp4clj.)

### Middleware

For cross-cutting concerns of your request and notification handlers, consider middleware functions:

```clojure
(defn wrap-vthread
  "Middleware that executes requests in a virtual thread."
  [handler]
  (fn [method context params]
    (promesa.core/vthread (handler method context params))))

;; ...

(defmulti receive-request (fn [method _ _] method))

(def server (server/chan-server {:request-handler (wrap-vthread #'receive-request)}))
```

### Async requests

lsp4clj passes the language server the client's messages one at a time. It won't provide another message until it receives a result from the message handlers. Therefore, by default, requests and notifications are processed in series.

However, it's possible to calculate requests in parallel (though not notifications). If the language server wants a request to be calculated in parallel with others, it should return a `java.util.concurrent.CompletableFuture`, possibly created with `promesa.core/future`, from the request handler. lsp4clj will arrange for the result of this future to be returned to the client when it resolves. In the meantime, lsp4clj will continue passing the client's messages to the language server. The language server can control the number of simultaneous messages by setting the parallelism of the CompletableFutures' executor.

### Cancelled inbound requests

Clients sometimes send `$/cancelRequest` notifications to indicate they're no longer interested in a request. If the request is being calculated in series, lsp4clj won't see the cancellation notification until after the response is already generated, so it's not possible to cancel requests that are processed in series.

But clients can cancel requests that are processed in parallel. In these cases lsp4clj will cancel the future and return a message to the client acknowledging the cancellation. Because of the design of CompletableFuture, cancellation can mean one of two things. If the executor hasn't started the thread that is calculating the value of the future (perhaps because the executor's thread pool is full), it won't be started. But if there is already a thread calculating the value, the thread won't be interupted. See the documentation for CompletableFuture for an explanation of why this is so.

Nevertheless, lsp4clj gives language servers a tool to abort cancelled requests. In the request's `context`, there will be a key `:lsp4clj.server/req-cancelled?` that can be dereffed to check if the request has been cancelled. If it has, then the language server can abort whatever it is doing. If it fails to abort, there are no consequences except that it will do more work than necessary.

```clojure
(defmethod receive-request "textDocument/semanticTokens/full"
  [_ {:keys [:lsp4clj.server/req-cancelled?] :as context} params]
  (promesa.core/future
    ;; client may cancel request while we are waiting for analysis
    (wait-for-analysis context)
    (when-not @req-cancelled?
      (handler/semantic-tokens-full context params))))
```

### Send messages

Servers also send their own requests and notifications to a client. To send a notification, call `lsp4clj.server/send-notification`.

```clojure
(->> {:message message
      :type type
      :extra extra}
     (conform-or-log ::coercer/show-message)
     (lsp4clj.server/send-notification server "window/showMessage"))
```

Sending a request is similar, with `lsp4clj.server/send-request`. This method returns a request object which may be dereffed to get the client's response. Most of the time you will want to call `lsp4clj.server/deref-or-cancel`, which will send a `$/cancelRequest` to the client if a timeout is reached before the client responds.

```clojure
(let [request (->> {:edit edit}
                   (conform-or-log ::coercer/workspace-edit-params)
                   (lsp4clj.server/send-request server "workspace/applyEdit"))
      response (lsp4clj.server/deref-or-cancel request 10e3 ::timeout)]
  (if (= ::timeout response)
    (logger/error "No reponse from client after 10 seconds.")
    response))
```

The request object presents the same interface as `future`. It responds to `realized?`, `future?`, `future-done?` and `future-cancelled?`.

If you call `future-cancel` on the request object, the server will send the client a `$/cancelRequest`. `$/cancelRequest` is sent only once, although `lsp4clj.server/deref-or-cancel` or `future-cancel` can be called multiple times. After a request is cancelled, later invocations of `deref` will return `:lsp4clj.server/cancelled`.

Alternatively, you can convert the request to a promesa promise, and handle it using that library's tools:

```clojure
(let [request (lsp4clj.server/send-request server "..." params)]
  (-> request
      (promesa/promise)
      (promesa/then (fn [response] {:result :client-success
                                    :value 1
                                    :resp response}))
      (promesa/catch (fn [ex-response] {:result :client-error
                                        :value 10
                                        :resp (ex-data ex-response)}))
      (promesa/timeout 10000 {:result :timeout
                              :value 100})
      (promesa/then #(update % :value inc))))
```

In this case `(promesa/cancel! request)` will send `$/cancelRequest`.

Response promises are completed on Promesa's `:default` executor.  You
can specify your own executor by passing the `:response-executor` option
when creating your server instance.

### Start and stop a server

The last step is to start the server you created earlier. Use `lsp4clj.server/start`. This method accepts two arguments, the server and a "context".

The context should be `associative?`. Whatever you provide in the context will be passed as the second argument to the notification and request `defmethod`s you defined earlier. This is a convenient way to make components of your system available to those methods without definining global constants. Often the context will include the server itself so that you can initiate outbound requests and notifications in reaction to inbound messages. lsp4clj reserves the right to add its own data to the context, using keys namespaced with `:lsp4clj.server/...`.

```clojure
(lsp4clj.server/start server {:custom-settings custom-settings, :logger logger})
```

The return of `start` is a promise that will resolve to `:done` when the server shuts down, which can happen in a few ways.

First, if the server's input is closed, it will shut down too. Second, if you call `lsp4clj.server/shutdown` on it, it will shut down.

When a server shuts down it stops reading input, finishes processing the messages it has in flight, and then closes is output. Finally it closes its `:log-ch` and `:trace-ch`. As such, it should probably not be shut down until the LSP `exit` notification (as opposed to the `shutdown` request) to ensure all messages are received. `lsp4clj.server/shutdown` will not return until all messages have been processed, or until 10 seconds have passed, whichever happens sooner. It will return `:done` in the first case and `:timeout` in the second.

## Other types of servers

So far the examples have focused on `lsp4clj.io-server/stdio-server`, because many clients communicate over stdio by default. The client opens a subprocess for the LSP server, then starts sending messages to the process via the process's stdin and reading messages from it on its stdout.

Many clients can also communicate over a socket. Typically the client starts a socket server, then passes a command-line argument to the LSP subprocess, telling it what port to connect to. The server is expected to connect to that port and use it to send and receive messages. In lsp4clj, that can be accomplished with `lsp4clj.io-server/server`:

```clojure
(defn socket-server [{:keys [host port]}]
  {:pre [(or (nil? host) (string? host))
         (and (int? port) (<= 1024 port 65535))]}
  (let [addr (java.net.InetAddress/getByName host) ;; nil host == loopback
        sock (java.net.Socket. ^java.net.InetAddress addr ^int port)]
    (lsp4clj.io-server/server {:in sock
                               :out sock})))
```

`lsp4clj.io-server/server` accepts a pair of options `:in` and `:out`. These will be coerced to a `java.io.InputStream` and `java.io.OutputStream` via `clojure.java.io/input-stream` and `clojure.java.io/output-stream`, respectively. The example above works because a `java.net.Socket` can be coerced to both an input and output stream via this mechanism.

A similar approach can be used to connect over pipes.

## Development details

### Tracing

As you are implementing, you may want to trace incoming and outgoing messages. Initialize the server with `:trace-level "verbose"` and then read traces (two element vectors, beginning with the log level `:debug` and ending with a string, the trace itself) off its `:trace-ch`.

```clojure
(let [server (lsp4clj.io-server/stdio-server {:trace-level "verbose"})]
  (async/go-loop []
    (when-let [[level trace] (async/<! (:trace-ch server))]
      (logger/log level trace)
      (recur)))
  (lsp4clj.server/start server context))
```

`:trace-level` can be set to `"off"` (no tracing), `"messages"` (to show just the message time, method, id and direction), or `"verbose"` (to also show details of the message body).

The trace level can be changed during the life of a server by calling, for example, `(ls4clj.server/set-trace-level server "messages")`. This can be used to respect a trace level received at runtime, either in an [initialize](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initializeParams) request or a [$/setTrace](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#setTrace) notification.

### Testing

A client is in many ways like a server—it also sends and receives requests and notifications and receives responses. That is, LSP uses JSON-RPC as a bi-directional protocol. As such, you may be able to use some of lsp4clj's tools to build a mock client for testing. See `integration.client` in `clojure-lsp` for one such example.

You may also find `lsp4clj.server/chan-server` a useful alternative to `stdio-server`. This server reads and writes off channels, instead of stdio streams. See `lsp4clj.server-test` for many examples of interacting with such a server.

## Caveats

You must not print to stdout while a `stdio-server` is running. This will corrupt its output stream. Clients will receive malformed messages, and either throw errors or stop responding.

From experience, it's dismayingly easy to leave in an errant `prn` or `time` and end up with a non-responsive client. For this reason, we highly recommend supporting communication over sockets (see [other types of servers](#other-types-of-servers)) which are immune to this problem. However, since the choice of whether to use sockets or stdio is ultimately up to the client, you may have no choice but to support both.

lsp4clj provides one tool to avoid accidental writes to stdout (or rather to `*out*`, which is usually the same as `System.out`). To protect a block of code from writing to `*out*`, wrap it with `lsp4clj.server/discarding-stdout`. The request and notification handlers are already protected this way, but tasks started outside of these handlers or that run in separate threads need this protection added.

## Known lsp4clj users

- [clojure-lsp](https://clojure-lsp.io/): A Clojure LSP server implementation.

## Release

To release a new version, run `bb tag x.y.z`, it should do all necessary changes and trigger a Github Action to deploy to clojars the new version.
