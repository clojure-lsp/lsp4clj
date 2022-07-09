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

(defn error-response [code message data]
  {:error {:code code
           :message message
           :data data}})

(def error-codes
  {:json-rpc/parse-error      {:code -32700, :message "Parse error"}
   :json-rpc/invalid-request  {:code -32600, :message "Invalid Request"}
   :json-rpc/method-not-found {:code -32601, :message "Method not found"}
   :json-rpc/invalid-params   {:code -32602, :message "Invalid params"}
   :json-rpc/internal-error   {:code -32603, :message "Internal error"}

   :lsp-rpc/server-not-initialized {:code -32002, :message "Server not initialized"}
   :lsp-rpc/unknown-error-code     {:code -32001, :message "Unknown error"}
   :lsp-rpc/request-failed         {:code -32803, :message "Request failed"}
   :lsp-rpc/server-cancelled       {:code -32802, :message "Server cancelled"}
   :lsp-rpc/content-modified       {:code -32801, :message "Content modified"}
   :lsp-rpc/request-cancelled      {:code -32800, :message "Request cancelled"}})

(defn standard-error-response [code-name data]
  (let [{:keys [code message]} (get error-codes code-name)]
    (error-response code message data)))

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
