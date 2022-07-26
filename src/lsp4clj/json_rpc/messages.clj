(ns lsp4clj.json-rpc.messages)

(set! *warn-on-reflection* true)

(def base-message {:jsonrpc "2.0"})

(defn request
  ([method params] ;; notification
   (assoc base-message
          :method method
          :params params))
  ([id method params] ;; request
   (-> (request method params)
       (assoc :id id))))

(defn response
  [id result]
  (let [response (assoc base-message :id id)]
    (if (and (map? result) (:error result))
      (assoc response :error (:error result))
      (assoc response :result result))))

(def error-codes
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

(defn error-result [code-or-name message data]
  (let [default (get error-codes code-or-name)]
    {:error (cond-> {:code (if default (:code default) code-or-name)
                     :message (or message (:message default))}
              data (assoc :data data))}))

(defn standard-error-result
  [code-name data]
  (error-result code-name nil data))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn work-done-progress [percentage message progress-token]
  (let [percentage (int percentage)
        progress {:kind (case percentage
                          0 :begin
                          100 :end
                          :report)
                  :title message
                  :percentage percentage}]
    {:token (or progress-token "lsp4clj")
     :value progress}))
