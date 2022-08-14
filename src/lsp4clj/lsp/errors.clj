(ns lsp4clj.lsp.errors)

(set! *warn-on-reflection* true)

(def by-key
  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#errorCodes
  {;; JSON-RPC errors
   ;; Is it possible to respond if you have a parse error? How could you reply
   ;; to a request if you weren't able to parse it?
   :parse-error      {:code -32700, :message "Parse error"}
   ;; Similarly for invalid-request. How could you reply to a request if it
   ;; doesn't look like a request?
   :invalid-request  {:code -32600, :message "Invalid Request"}
   :method-not-found {:code -32601, :message "Method not found"}
   :invalid-params   {:code -32602, :message "Invalid params"}
   :internal-error   {:code -32603, :message "Internal error"}

   ;; LSP errors
   :server-not-initialized {:code -32002, :message "Server not initialized"}
   :unknown-error-code     {:code -32001, :message "Unknown error"}
   :request-failed         {:code -32803, :message "Request failed"}
   :server-cancelled       {:code -32802, :message "Server cancelled"}
   :content-modified       {:code -32801, :message "Content modified"}
   :request-cancelled      {:code -32800, :message "Request cancelled"}})

(defn by-code
  "Returns a JSON-RPC error object, for use in an error response.

  https://www.jsonrpc.org/specification#error_object

  If `code-or-key` is one of the keywords defined in `by-key`, creates an error
  object with the corresponding numeric code. Otherwise, `code-or-key` should
  be an integer, excluding the ranges -32000 to -32099 and -32800 to -32899,
  which are reserved."
  [code-or-key]
  (or (get by-key code-or-key)
      {:code code-or-key}))

(defn body
  "Returns a JSON-RPC error object with the code and, if provided, the message
  and data.

  `code` can be a keyword or an integer as per `by-code`."
  [code message data]
  (cond-> (by-code code)
    message (assoc :message message)
    data (assoc :data data)))

(defn internal-error [data]
  (-> (by-code :internal-error)
      (assoc :data data)))

(defn not-found [method]
  (-> (by-code :method-not-found)
      (assoc :data {:method method})))
