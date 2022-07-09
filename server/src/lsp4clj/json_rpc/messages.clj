(ns lsp4clj.json-rpc.messages)

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
  {;; JSON-RPC errors
   :parse-error      {:code -32700, :message "Parse error"}
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

(defn error-response [code-or-name message data]
  (let [default (get error-codes code-or-name)]
    {:error {:code (if default (:code default) code-or-name)
             :message (or message (:message default))
             :data data}}))

(defn standard-error-response
  [code-name data]
  (error-response code-name nil data))

(defn work-done-progress [percentage message progress-token]
  (let [percentage (int percentage)
        progress {:kind (case percentage
                          0 :begin
                          100 :end
                          :report)
                  :title message
                  :percentage percentage}]
    {:token (or progress-token "clojure-lsp")
     :value progress}))
