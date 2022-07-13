(ns lsp4clj.server
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :as pprint]
   [lsp4clj.json-rpc :as json-rpc]
   [lsp4clj.json-rpc.messages :as json-rpc.messages]
   [lsp4clj.protocols.endpoint :as protocols.endpoint]
   [lsp4clj.protocols.logger :as logger]
   [lsp4clj.trace :as trace]))

(set! *warn-on-reflection* true)

(defprotocol IBlockingDerefOrCancel
  (deref-or-cancel [this timeout-ms timeout-val]))

(defrecord PendingRequest [p cancelled? id method started server]
  clojure.lang.IDeref
  (deref [_] (deref p))
  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-val]
    (deref p timeout-ms timeout-val))
  IBlockingDerefOrCancel
  (deref-or-cancel [this timeout-ms timeout-val]
    (let [result (deref this timeout-ms ::timeout)]
      (if (= ::timeout result)
        (do (future-cancel this)
            timeout-val)
        result)))
  clojure.lang.IPending
  (isRealized [_] (realized? p))
  java.util.concurrent.Future
  (get [this]
    (let [result (deref this)]
      (if (= ::cancelled result)
        (throw (java.util.concurrent.CancellationException.))
        result)))
  (get [this timeout unit]
    (let [result (deref this (.toMillis unit timeout) ::timeout)]
      (case result
        ::cancelled (throw (java.util.concurrent.CancellationException.))
        ::timeout (throw (java.util.concurrent.TimeoutException.))
        result)))
  (isCancelled [_] @cancelled?)
  (isDone [this] (or (.isRealized this) (.isCancelled this)))
  (cancel [this _interrupt?]
    (if (.isDone this)
      false
      (do
        (reset! cancelled? true)
        (protocols.endpoint/send-notification server "$/cancelRequest" {:id id})
        (deliver p ::cancelled)
        true))))

;; Avoid error: java.lang.IllegalArgumentException: Multiple methods in multimethod 'simple-dispatch' match dispatch value: class lsp4clj.server.PendingRequest -> interface clojure.lang.IPersistentMap and interface clojure.lang.IDeref, and neither is preferred
;; Only when CIDER is running? See https://github.com/thi-ng/color/issues/10
(prefer-method pprint/simple-dispatch clojure.lang.IDeref clojure.lang.IPersistentMap)

(defn pending-request
  "Returns an object representing a pending JSON-RPC request to a remote
  endpoint. Deref the object to get the response.

  Most of the time, you should call `lsp4clj.server/deref-or-cancel` on the
  object. This has the same signature as `clojure.core/deref` with a timeout. If
  the client produces a response, will return it, but if the timeout is reached
  will cancel the request by sending a `$/cancelRequest` notification to the
  client.

  Otherwise, the object presents the same interface as `future`. Responds to
  `future-cancel` (which sends `$/cancelRequest`), `realized?`, `future-done?`
  and `future-cancelled?`.

  If the request is cancelled, future invokations of `deref` will return
  `:lsp4clj.server/cancelled`.

  Sends `$/cancelRequest` only once, though it is permitted to call
  `lsp4clj.server/deref-or-cancel` and `future-cancel` multiple times."
  [id method started server]
  (map->PendingRequest {:p (promise)
                        :cancelled? (atom false)
                        :id id
                        :method method
                        :started started
                        :server server}))

(defn ^:private receive-message
  [server context message]
  (if-let [{:keys [id method] :as json} message]
    (try
      (cond
        (and id method) (protocols.endpoint/receive-request server context json)
        id              (do (protocols.endpoint/receive-response server json)
                            ;; Ensure server doesn't respond to responses
                            nil)
        :else           (do (protocols.endpoint/receive-notification server context json)
                            ;; Ensure server doesn't respond to notifications
                            nil))
      (catch Throwable e
        ;; TODO: if this was a request, send a generic -32603 Internal error
        (logger/debug e "exception receiving")))
    ;; TODO: what does a nil message signify? What should we do with it?
    (logger/debug "client closed? or json parse error?")))

;; Expose endpoint methods to language servers

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def start protocols.endpoint/start)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def shutdown protocols.endpoint/shutdown)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def exit protocols.endpoint/exit)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def send-request protocols.endpoint/send-request)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def send-notification protocols.endpoint/send-notification)

;; Let language servers implement their own message receivers. These are
;; slightly different from protocols.endpont/receive-request, in that they
;; receive the message params, not the whole message.

(defmulti receive-request (fn [method _context _params] method))
(defmulti receive-notification (fn [method _context _params] method))

(defmethod receive-request :default [method _context _params]
  (logger/debug "received unexpected request" method)
  (json-rpc.messages/standard-error-response :method-not-found {:method method}))

(defmethod receive-notification :default [method _context _params]
  (logger/debug "received unexpected notification" method))

(defrecord ChanServer [parallelism
                       trace-ch
                       input
                       output
                       ^java.time.Clock clock
                       request-id*
                       pending-requests*
                       join]
  protocols.endpoint/IEndpoint
  (start [this context]
    (let [pipeline (async/pipeline-blocking
                     parallelism
                     output
                     ;; TODO: return error until initialize request is received? https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
                     ;; `keep` means we do not reply to responses and notifications
                     (keep #(receive-message this context %))
                     input)]
      (async/go
        ;; wait for pipeline to close, indicating input closed
        (async/<! pipeline)
        (deliver join :done)))
    ;; invokers should deref the return of `start`, so the server stays alive
    ;; until it is shut down
    join)
  (shutdown [_this]
    ;; closing input will drain pipeline, then close output, then close
    ;; pipeline, then deliver join
    (async/close! input)
    (deref join 10e3 :timeout))
  (exit [_this])
  (send-request [this method body]
    (let [id (swap! request-id* inc)
          now (.instant clock)
          req (json-rpc.messages/request id method body)
          pending-request (pending-request id method now this)]
      (some-> trace-ch (async/put! (trace/sending-request id method body now)))
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! pending-requests* assoc id pending-request)
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      (async/>!! output req)
      pending-request))
  (send-notification [_this method body]
    (let [now (.instant clock)
          notif (json-rpc.messages/request method body)]
      (some-> trace-ch (async/put! (trace/sending-notification method body now)))
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      (async/>!! output notif)))
  (receive-response [_this {:keys [id] :as resp}]
    (let [now (.instant clock)]
      (if-let [{:keys [id method p started]} (get @pending-requests* id)]
        (let [error (:error resp)
              result (:result resp)]
          (some-> trace-ch (async/put! (trace/received-response id method result error started now)))
          (swap! pending-requests* dissoc id)
          (deliver p (if error resp result)))
        (some-> trace-ch (async/put! (trace/received-unmatched-response now resp))))))
  (receive-request [_this context {:keys [id method params]}]
    (let [started (.instant clock)]
      (some-> trace-ch (async/put! (trace/received-request id method params started)))
      (let [result (receive-request method context params)
            resp (json-rpc.messages/response id result)
            finished (.instant clock)]
        (some-> trace-ch (async/put! (trace/sending-response id method result started finished)))
        resp)))
  (receive-notification [_this context {:keys [method params]}]
    (some-> trace-ch (async/put! (trace/received-notification method params (.instant clock))))
    (receive-notification method context params)))

(defn chan-server [{:keys [output input parallelism trace? clock]
                    :or {parallelism 4, trace? false, clock (java.time.Clock/systemDefaultZone)}}]
  (map->ChanServer
    {:parallelism parallelism
     :output output
     :input input
     :trace-ch (when trace? (async/chan (async/sliding-buffer 20)))
     :clock clock
     :request-id* (atom 0)
     :pending-requests* (atom {})
     :join (promise)}))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn stdio-server [{:keys [in out] :as opts}]
  (chan-server (assoc opts
                      :input (json-rpc/input-stream->input-chan in)
                      :output (json-rpc/output-stream->output-chan out))))
