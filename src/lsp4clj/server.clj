(ns lsp4clj.server
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :as pprint]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.lsp.errors :as lsp.errors]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.lsp.responses :as lsp.responses]
   [lsp4clj.protocols.endpoint :as protocols.endpoint]
   [lsp4clj.trace :as trace]))

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
  (protocols.endpoint/log server :error e
                          (str (format-error-code "Error receiving message" :internal-error) "\n"
                               (select-keys message [:id :method]))))

(defn ^:private start-pipeline [input-ch output-ch server context]
  ;; Fork the input off to two streams of work, the input initiated by the
  ;; client (the client's requests and notifications) and the input initiated by
  ;; the server (the client's responses). Process each stream one message at a
  ;; time, but independently. The streams must be processed indepedently so that
  ;; while receiving a request, the server can send a request and receive the
  ;; response before sending its response to the original request. This happens,
  ;; for example, when servers send showMessageRequest while processing a
  ;; request they have received.
  (let [server-initiated-ch (async/chan 1)
        client-initiated-ch (async/chan 1)
        pipeline
        (async/go-loop []
          (if-let [message (async/<! input-ch)]
            (let [message-type (coercer/input-message-type message)]
              (case message-type
                (:parse-error :invalid-request)
                (protocols.endpoint/log server :error (format-error-code "Error reading message" message-type))
                (:request :notification)
                (async/>! client-initiated-ch [message-type message])
                (:response.result :response.error)
                (async/>! server-initiated-ch message))
              (recur))
            (do
              (async/close! client-initiated-ch)
              (async/close! server-initiated-ch)
              (async/close! output-ch))))]
    ;; a thread so server can use >!! and so that we can use (>!! output-ch) to
    ;; respect back pressure from clients that are slow to read.
    (async/thread
      (discarding-stdout
        (loop []
          (when-let [[message-type message] (async/<!! client-initiated-ch)]
            ;; TODO: return error until initialize response is sent?
            ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
            (case message-type
              :request
              (async/>!! output-ch
                         (try
                           (protocols.endpoint/receive-request server context message)
                           (catch Throwable e
                             (log-error-receiving server e message)
                             (-> (lsp.responses/response (:id message))
                                 (lsp.responses/error (lsp.errors/internal-error (select-keys message [:id :method])))))))
              :notification
              (try
                (protocols.endpoint/receive-notification server context message)
                (catch Throwable e
                  (log-error-receiving server e message))))
            (recur)))))
    (async/go-loop []
      (when-let [message (async/<! server-initiated-ch)]
        (protocols.endpoint/receive-response server message)
        (recur)))
    pipeline))

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
;; Servers can't implement cancellation of inbound requests themselves, because
;; lsp4clj manages request ids. Until lsp4clj adds support, ignore cancellation
;; requests.
(defmethod receive-notification "$/cancelRequest" [_ _ _])

(defrecord ChanServer [input-ch
                       output-ch
                       trace-ch
                       log-ch
                       ^java.time.Clock clock
                       on-close
                       request-id*
                       pending-requests*
                       join]
  protocols.endpoint/IEndpoint
  (start [this context]
    (let [pipeline (start-pipeline input-ch output-ch this context)]
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
      (some-> trace-ch (async/put! (trace/sending-request req now)))
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! pending-requests* assoc id pending-request)
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      (async/>!! output-ch req)
      pending-request))
  (send-notification [_this method body]
    (let [now (.instant clock)
          notif (lsp.requests/notification method body)]
      (some-> trace-ch (async/put! (trace/sending-notification notif now)))
      ;; respect back pressure from clients that are slow to read; (go (>!)) will not suffice
      (async/>!! output-ch notif)))
  (receive-response [_this {:keys [id error result] :as resp}]
    (let [now (.instant clock)
          [pending-requests _] (swap-vals! pending-requests* dissoc id)]
      (if-let [{:keys [p started] :as req} (get pending-requests id)]
        (do
          (some-> trace-ch (async/put! (trace/received-response req resp started now)))
          (deliver p (if error resp result)))
        (some-> trace-ch (async/put! (trace/received-unmatched-response resp now))))))
  (receive-request [this context {:keys [id method params] :as req}]
    (let [started (.instant clock)]
      (some-> trace-ch (async/put! (trace/received-request req started)))
      (let [result (receive-request method context params)
            resp (lsp.responses/response id)
            resp (if (identical? ::method-not-found result)
                   (do
                     (protocols.endpoint/log this :warn "received unexpected request" method)
                     (lsp.responses/error resp (lsp.errors/not-found method)))
                   (lsp.responses/infer resp result))
            finished (.instant clock)]
        (some-> trace-ch (async/put! (trace/sending-response req resp started finished)))
        resp)))
  (receive-notification [this context {:keys [method params] :as notif}]
    (some-> trace-ch (async/put! (trace/received-notification notif (.instant clock))))
    (let [result (receive-notification method context params)]
      (when (identical? ::method-not-found result)
        (protocols.endpoint/log this :warn "received unexpected notification" method)))))

(defn chan-server
  [{:keys [output-ch input-ch log-ch trace? trace-ch clock on-close]
    :or {clock (java.time.Clock/systemDefaultZone)
         on-close (constantly nil)}}]
  (map->ChanServer
    {:output-ch output-ch
     :input-ch input-ch
     :trace-ch (or trace-ch (and trace? (async/chan (async/sliding-buffer 20))))
     :log-ch (or log-ch (async/chan (async/sliding-buffer 20)))
     :clock clock
     :on-close on-close
     :request-id* (atom 0)
     :pending-requests* (atom {})
     :join (promise)}))
