(ns lsp4clj.server
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :as pprint]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.lsp.errors :as lsp.errors]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.lsp.responses :as lsp.responses]
   [lsp4clj.protocols.endpoint :as protocols.endpoint]
   [lsp4clj.trace :as trace]
   [promesa.core :as p])
  (:import
   (java.util.concurrent CancellationException)))

(set! *warn-on-reflection* true)

(def null-output-stream-writer
  (java.io.OutputStreamWriter.
    (proxy [java.io.OutputStream] []
      (write
        ([^bytes b])
        ([^bytes b, off, len])))))

(defmacro discarding-stdout
  "Evaluates body in a context in which writes to *out* are discarded."
  [& body]
  `(binding [*out* null-output-stream-writer]
     ~@body))

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
      (if (compare-and-set! cancelled? false true)
        (do
          (protocols.endpoint/send-notification server "$/cancelRequest" {:id id})
          (deliver p ::cancelled)
          true)
        false))))

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
  `future-cancel` (which sends `$/cancelRequest`), `realized?`, `future?`
  `future-done?` and `future-cancelled?`.

  If the request is cancelled, future invocations of `deref` will return
  `:lsp4clj.server/cancelled`.

  Sends `$/cancelRequest` only once, though `lsp4clj.server/deref-or-cancel` or
  `future-cancel` can be called multiple times."
  [id method started server]
  (map->PendingRequest {:p (promise)
                        :cancelled? (atom false)
                        :id id
                        :method method
                        :started started
                        :server server}))

(defn ^:private format-error-code [description error-code]
  (let [{:keys [code message]} (lsp.errors/by-key error-code)]
    (format "%s: %s (%s)" description message code)))

(defn ^:private log-error-receiving [server e message]
  (let [message-details (select-keys message [:id :method])
        log-title (format-error-code "Error receiving message" :internal-error)]
    (protocols.endpoint/log server :error e (str log-title "\n" message-details))))

(defn ^:private receive-message
  [server context message]
  (let [message-type (coercer/input-message-type message)]
    (try
      (discarding-stdout
        (case message-type
          (:parse-error :invalid-request)
          (protocols.endpoint/log server :error (format-error-code "Error reading message" message-type))
          :request
          (protocols.endpoint/receive-request server context message)
          (:response.result :response.error)
          (protocols.endpoint/receive-response server message)
          :notification
          (protocols.endpoint/receive-notification server context message)))
      (catch Throwable e ;; exceptions thrown by receive-response or receive-notification (receive-request catches its own exceptions)
        (log-error-receiving server e message)))))

;; Expose endpoint methods to language servers

(def start protocols.endpoint/start)
(def shutdown protocols.endpoint/shutdown)
(def send-request protocols.endpoint/send-request)
(def send-notification protocols.endpoint/send-notification)

;; Let language servers implement their own message receivers. These are
;; slightly different from the identically named protocols.endpoint versions, in
;; that they receive the message params, not the whole message.

(defmulti receive-request (fn [method _context _params] method))
(defmulti receive-notification (fn [method _context _params] method))

(defmethod receive-request :default [_method _context _params] ::method-not-found)
(defmethod receive-notification :default [_method _context _params] ::method-not-found)

(defn ^:private internal-error-response [resp req]
  (let [error-body (lsp.errors/internal-error (select-keys req [:id :method]))]
    (lsp.responses/error resp error-body)))

(defn ^:private cancellation-response [resp req]
  (let [message-details (select-keys req [:id :method])
        error-body (lsp.errors/body :request-cancelled
                                    (format "The request %s has been cancelled."
                                            (pr-str message-details))
                                    message-details)]
    (lsp.responses/error resp error-body)))

(defn trace [{:keys [tracer* trace-ch]} trace-f & params]
  (when-let [trace-body (apply trace-f @tracer* params)]
    (async/put! trace-ch trace-body)))

;; TODO: https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
;; * receive-request should return error until initialize request is received
;; * receive-notification should drop until initialize request is received, with the exception of exit
;; * send-request should do nothing until initialize response is sent, with the exception of window/showMessageRequest
;; * send-notification should do nothing until initialize response is sent, with the exception of window/showMessage, window/logMessage, telemetry/event, and $/progress
(defrecord ChanServer [input-ch
                       output-ch
                       log-ch
                       trace-ch
                       tracer*
                       ^java.time.Clock clock
                       on-close
                       request-id*
                       pending-sent-requests*
                       pending-received-requests*
                       join]
  protocols.endpoint/IEndpoint
  (start [this context]
    (let [;; a thread so language server can use >!! and so that receive-message
          ;; can use (>!! output-ch) to respect back pressure from clients that
          ;; are slow to read.
          pipeline (async/thread
                     (loop []
                       (if-let [message (async/<!! input-ch)]
                         (do
                           (receive-message this context message)
                           (recur))
                         (async/close! output-ch))))]
      (async/go
        ;; Wait for pipeline to close. This indicates input-ch was closed and
        ;; that now output-ch is closed.
        (async/<! pipeline)
        ;; Do additional cleanup.
        (async/close! log-ch)
        (some-> trace-ch async/close!)
        (on-close)
        (deliver join :done)))
    ;; invokers can deref the return of `start` to stay alive until server is
    ;; shut down
    join)
  (shutdown [_this]
    ;; Closing input-ch will drain pipeline then close it which, in turn,
    ;; triggers additional cleanup.
    (async/close! input-ch)
    (deref join 10e3 :timeout))
  (log [_this level arg1]
    (async/put! log-ch [level arg1]))
  (log [_this level arg1 arg2]
    (async/put! log-ch [level arg1 arg2]))
  (send-request [this method body]
    (let [id (swap! request-id* inc)
          now (.instant clock)
          req (lsp.requests/request id method body)
          pending-request (pending-request id method now this)]
      (trace this trace/sending-request req now)
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! pending-sent-requests* assoc id pending-request)
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      (async/>!! output-ch req)
      pending-request))
  (send-notification [this method body]
    (let [now (.instant clock)
          notif (lsp.requests/notification method body)]
      (trace this trace/sending-notification notif now)
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      (async/>!! output-ch notif)))
  (receive-response [this {:keys [id error result] :as resp}]
    (let [now (.instant clock)
          [pending-requests _] (swap-vals! pending-sent-requests* dissoc id)]
      (if-let [{:keys [p started] :as req} (get pending-requests id)]
        (do
          (trace this trace/received-response req resp started now)
          (deliver p (if error resp result)))
        (trace this trace/received-unmatched-response resp now))))
  (receive-request [this context {:keys [id method params] :as req}]
    (let [started (.instant clock)
          resp (lsp.responses/response id)]
      (try
        (trace this trace/received-request req started)
        ;; coerce result/error to promise
        (let [result-promise (p/promise (receive-request method context params))]
          (swap! pending-received-requests* assoc id result-promise)
          (-> result-promise
              ;; convert result/error to response
              (p/then
                (fn [result]
                  (if (identical? ::method-not-found result)
                    (do
                      (protocols.endpoint/log this :warn "received unexpected request" method)
                      (lsp.responses/error resp (lsp.errors/not-found method)))
                    (lsp.responses/infer resp result))))
              ;; Handle
              ;; 1. Exceptions thrown within p/future created by receive-request.
              ;; 2. Cancelled requests.
              (p/catch
               (fn [e]
                 (if (instance? CancellationException e)
                   (cancellation-response resp req)
                   (do
                     (log-error-receiving this e req)
                     (internal-error-response resp req)))))
              (p/finally
                (fn [resp _error]
                  (swap! pending-received-requests* dissoc id)
                  (trace this trace/sending-response req resp started (.instant clock))
                  (async/>!! output-ch resp)))))
        (catch Throwable e ;; exceptions thrown by receive-request
          (log-error-receiving this e req)
          (async/>!! output-ch (internal-error-response resp req))))))
  (receive-notification [this context {:keys [method params] :as notif}]
    (let [now (.instant clock)]
      (trace this trace/received-notification notif now)
      (if (= method "$/cancelRequest")
        (if-let [result-promise (get @pending-received-requests* (:id params))]
          (p/cancel! result-promise)
          (trace this trace/received-unmatched-cancellation-notification notif now))
        (let [result (receive-notification method context params)]
          (when (identical? ::method-not-found result)
            (protocols.endpoint/log this :warn "received unexpected notification" method)))))))

(defn set-trace-level [server trace-level]
  (update server :tracer* reset! (trace/tracer-for-level trace-level)))

(defn chan-server
  [{:keys [output-ch input-ch log-ch trace? trace-level trace-ch clock on-close]
    :or {clock (java.time.Clock/systemDefaultZone)
         on-close (constantly nil)}}]
  (let [trace-level (or trace-level
                        (when (or trace? trace-ch) "verbose")
                        "off")]
    (map->ChanServer
      {:output-ch output-ch
       :input-ch input-ch
       :log-ch (or log-ch (async/chan (async/sliding-buffer 20)))
       :trace-ch (or trace-ch (async/chan (async/sliding-buffer 20)))
       :tracer* (atom (trace/tracer-for-level trace-level))
       :clock clock
       :on-close on-close
       :request-id* (atom 0)
       :pending-sent-requests* (atom {})
       :pending-received-requests* (atom {})
       :join (promise)})))
