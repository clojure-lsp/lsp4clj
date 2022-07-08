(ns lsp4clj.server
  (:require
   [clojure.core.async :as async]
   [lsp4clj.json-rpc :as json-rpc]
   [lsp4clj.protocols.endpoint :as protocols.endpoint]
   [lsp4clj.protocols.logger :as logger]))

(defn ^:private receive-message
  [server message]
  (if-let [{:keys [id method] :as json} message]
    (try
      (cond
        (and id method) (protocols.endpoint/receive-request server json)
        id              (protocols.endpoint/receive-response server json)
        :else           (protocols.endpoint/receive-notification server json))
      (catch Throwable e
        (logger/debug e "listener closed: exception receiving")))
    (logger/debug "listener closed: client closed")))

;; TODO: Does LSP have a standard format for traces?
;; TODO: Are you supposed to trace before or after sending?
(defn ^:private trace-sending-request [req] (logger/debug "trace - sending request" req))
(defn ^:private trace-sending-notification [notif] (logger/debug "trace - sending notification" notif))
(defn ^:private trace-sending-response [resp] (logger/debug "trace - sending response" resp))
(defn ^:private trace-received-response [resp] (logger/debug "trace - received response" resp))
(defn ^:private trace-received-request [req] (logger/debug "trace - received request" req))
(defn ^:private trace-received-notification [notif] (logger/debug "trace - received notification" notif))

(defmulti handle-request (fn [method _params] method))
(defmulti handle-notification (fn [method _params] method))

(defmethod handle-request :default [method params]
  (logger/debug "received unexpected request" method params)
  {:error {:code -32601
           :message "Method not found"
           :data {:method method}}})

(defmethod handle-notification :default [method params]
  (logger/debug "received unexpected notification" method params))

(defrecord Server [parallelism
                   trace?
                   receiver
                   sender
                   request-id*
                   pending-requests*
                   on-shutdown]
  protocols.endpoint/IEndpoint
  (start [this]
    (async/pipeline-blocking parallelism
                             sender
                             ;; TODO: return error until initialize request is received? https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
                             ;; TODO: coerce here? Or leave that to servers?
                             (map #(receive-message this %))
                             receiver)
    ;; invokers should deref the return of `start`, so the server stays alive
    ;; until it is shut down
    on-shutdown)
  (shutdown [_this]
    (async/close! receiver)
    (deliver on-shutdown :done))
  (exit [_this] ;; wait for shutdown of client to propagate to receiver
    (async/<!! sender))
  (send-request [_this method body]
    (let [id (swap! request-id* inc)
          req (json-rpc/json-rpc-message id method body)
          p (promise)]
      (when trace? (trace-sending-request req))
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! pending-requests* assoc id p)
      (async/>!! sender req)
      p))
  (send-notification [_this method body]
    (let [notif (json-rpc/json-rpc-message method body)]
      (when trace? (trace-sending-notification notif))
      (async/>!! sender notif)))
  (receive-response [_this {:keys [id] :as resp}]
    (if-let [request (get @pending-requests* id)]
      (do (when trace? (trace-received-response resp))
          (swap! pending-requests* dissoc id)
          (deliver request (if (:error resp)
                             resp
                             (:result resp))))
      (logger/debug "received response for unmatched request:" resp)))
  (receive-request [_this {:keys [id method params] :as req}]
    (when trace? (trace-received-request req))
    (when-let [resp (handle-request method params)]
      (let [resp (if-let [error (:error resp)]
                   {:jsonrpc "2.0"
                    :id id
                    :error error}
                   {:jsonrpc "2.0"
                    :id id
                    :result resp})]
        (when trace? (trace-sending-response resp))
        (async/>!! sender resp))))
  (receive-notification [_this {:keys [method params] :as notif}]
    (when trace? (trace-received-notification notif))
    (handle-notification method params)))

(defn chan-server [{:keys [sender receiver parallelism trace?]
                     :or {parallelism 4, trace? false}}]
  (map->Server
    {:parallelism parallelism
     :trace? trace?
     :sender sender
     :receiver receiver
     :request-id* (atom 0)
     :pending-requests* (atom {})
     :on-shutdown (promise)}))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn stdio-server [{:keys [in out] :as opts}]
  (chan-server (assoc opts
                      :receiver (json-rpc/buffered-reader->receiver-chan in)
                      :sender (json-rpc/buffered-writer->sender-chan out))))
(comment
  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})]
    (prn "sending message to receiver")
    (async/put! receiver {:id 1
                          :method "foo"
                          :params {}})
    (prn "sent message to receiver")
    (let [started-server (protocols.endpoint/start server)]
      (prn "gettting message from sender")
      (async/<!! sender)
      (prn "got message from sender")
      (prn "sending message to receiver")
      (async/put! receiver {:id 2
                            :method "bar"
                            :params {}})
      (prn "sent message to receiver")
      (protocols.endpoint/shutdown server)
      (protocols.endpoint/exit server)
      @started-server))

  #_())
