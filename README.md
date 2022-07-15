# lsp4clj

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) base for developing any LSP implementation in Clojure.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-lsp/lsp4clj.svg)](https://clojars.org/com.github.clojure-lsp/lsp4clj)

lsp4clj reads and writes from stdio, parsing JSON-RPC according to the LSP spec. It provides tools to allow server implementors to receive, process, and respond to any of the methods defined in the LSP spec, and to send their own requests and notifications to clients.

## Usage

### Create a server

To initialize a server that will read from stdin and write to stdout:

```clojure
(lsp4clj.server/stdio-server {:in System/in, :out System/out})
```

The returned server will have a core.async `:log-ch`, from which you can read server logs (vectors beginning with a log level).

```clojure
(async/go-loop []
  (when-let [[level & args] (async/<! (:log-ch server))]
    (apply logger/warn level args)
    (recur)))
```

### Receive messages

To receive messages from a client, lsp4clj defines a pair of multimethods, `lsp4clj.server/receive-notification` and `lsp4clj.server/receive-request` that dispatch on the method name (as defined by the LSP spec) of an incoming JSON-RPC message.

Server implementors should create `defmethod`s for the messages they want to process. (Other methods will be logged and responded to with a generic "Method not found" response.)

These defmethods receive 3 arguments, the method name, a "context", and the `params` of the [JSON-RPC request or notification object](https://www.jsonrpc.org/specification#request_object). The params will have been converted to kebab-case keywords. Read on for an explanation of what a "context" is and how to set it.

```clojure
;; a notification; return value is ignored
(defmethod lsp4clj.server/receive-notification "textDocument/didOpen" [_ context {:keys [text-document]}]
  (handler/did-open context (:uri text-document) (:text text-document))
  
;; a request; return value is convert to a response
(defmethod lsp4clj.server/receive-request "textDocument/definition" [_ context params]
  (->> params
       (handler/definition context)
       (conform-or-log ::coercer/location)))
```

The return value of requests will be converted to camelCase json and returned to the client. If the return value looks like `{:error ...}`, it is assumed to indicate an error response, and the `...` part will be set as the `error` of a [JSON-RPC error object](https://www.jsonrpc.org/specification#error_object). It is up to you to conform the `...` object (by giving it a code, message, and data.) Otherwise, the entire return value will be set as the `result` of a [JSON-RPC response object](https://www.jsonrpc.org/specification#response_object). (Message ids are handled internally by lsp4clj.)

### Send messages

Servers also initiate their own requests and notifications to a client. To send a notification, call `lsp4clj.server/send-notification`.

```clojure
(->> {:message message
      :type type
      :extra extra}
     (conform-or-log ::coercer/show-message)
     (lsp4clj.server/send-notification server "window/showMessage"))
```

Sending a request is similar, with `lsp4clj.server/send-request`. This method returns a request object which may be dereffed to get the client's response. Most of the time you will want to call `lsp4clj.server/deref-or-cancel`, which will send a `$/cancelRequest` to the client if a timeout is reached.

```clojure
(let [request (->> {:edit edit}
                   (conform-or-log ::coercer/workspace-edit-params)
                   (lsp4clj.server/send-request server "workspace/applyEdit"))
      response (lsp4clj.server/deref-or-cancel request 10e3 ::timeout)]
  (if (= ::timeout response)
    (logger/error "No reponse from client after 10 seconds.")
    response))
```

### Start and stop a server

The last step is to start the server you created earlier. Use `lsp4clj.server/start`. This method accepts two arguments, the server and a "context". Whatever you provide for the context will be passed into the notification and request `defmethods` you defined earlier. This is a convenient way to make components of your system available to those methods without definining global constants. Often the context will include the server itself so that you can initiate outbound requests and notifications in reaction to inbound messages.

```clojure
(lsp4clj.server/start server {:server server, :logger logger})
```

The return of `start` is a promise that will resolve when the server shuts down, which can happen in a few ways.

First, if the server's input is closed, it will shutdown too. Second, if you call `lsp4clj.server/shutdown` on it, it will shutdown.

When a server shuts down it stops reading input, finishes processing the messages it has in flight, and then closes is output. (It also closes its `:log-ch` and `:trace-ch`.) As such, it should probably not be shut down until the LSP notification `exit` (as opposed to the `shutdown` request) to ensure all messages are received.

## Development details

As you are implementing, you may want to trace incoming and outgoing messages. Initialize the server with `:trace? true` and then read traces (strings) off its `:trace-ch`.

```clojure
(let [server (lsp4clj.server/stdio-server {:trace? true
                                           :in System/in
                                           :out System/out})]
  (async/go-loop []
    (when-let [trace (async/<! (:trace-ch server))]
      (logger/debug trace)
      (recur)))
  (lsp4clj.server/start server context))
```

For testing, observe that a client is in many ways like a server, in that it sends and receives requests and notifications. That is, LSP's flavor of JSON-RPC is bi-directional. As such, you may be able to use some of lsp4clj's tools to build a mock client for testing. See `integration.client` in `clojure-lsp` for one such example.

You may also find `lsp4clj.server/chan-server` a useful alternative to `stdio-server`. This server reads and writes off channels, insead of stdio streams.

## Caveats

You must not print to stdout while a server is running. This will corrupt its output stream and clients will receive malformed messages. To protect a block of code from writing to stdout, wrap it with `lsp4clj.server/discarding-stdout`. The `receive-notification` and `receive-request` multimethods are already protected this way, but tasks started outside of these multimethods need this protection added. See https://github.com/clojure-lsp/lsp4clj/issues/1 for future work on avoiding this problem.

## Known lsp4clj users

- [clojure-lsp](https://clojure-lsp.io/): A Clojure LSP server implementation.
