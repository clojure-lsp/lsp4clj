(ns lsp4clj.trace
  (:require
   [cheshire.core :as json]))

(set! *warn-on-reflection* true)

(defn ^:private format-tag [^java.time.Instant at]
  (format "[Trace - %s]"
          (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))))

(defn ^:private format-request-signature [{:keys [method id]}]
  (format "'%s - (%s)'" method id))

(defn ^:private format-notification-signature [{:keys [method]}]
  (format "'%s'" method))

(defn ^:private format-body [label body]
  (str label ": " (json/generate-string body {:pretty true})))

(defn ^:private format-params [{:keys [params]}]
  (format-body "Params" params))

(defn ^:private format-response-body [{:keys [error result]}]
  (if error
    (format-body "Error data" (:data error))
    (format-body "Result" result)))

(defn ^:private format-header [at direction message-type header-details]
  (str (format-tag at) " " direction " " message-type " " header-details))

(defn ^:private basic-trace [at direction message-type header-details]
  [:debug
   (format-header at direction message-type header-details)])

(defn ^:private verbose-trace [at direction message-type header-details body]
  [:debug
   (str (format-header at direction message-type header-details) "\n"
        body "\n\n\n")])

(defn ^:private latency [^java.time.Instant started ^java.time.Instant finished]
  (format "%sms" (- (.toEpochMilli finished) (.toEpochMilli started))))

(defn ^:private format-response-header-details [req {:keys [error]} started finished]
  (format
    (str "%s. Request took %s." (when error " Request failed: %s (%s)."))
    (format-request-signature req)
    (latency started finished)
    (:message error) (:code error)))

(defn ^:private format-unmatched-notif-header-details [notif]
  (format "for unmatched request (%s):" (:id (:params notif))))

(defn ^:private verbose-notification [direction notif at]
  (verbose-trace at direction "notification" (format-notification-signature notif)
                 (format-params notif)))

(defn ^:private verbose-request [direction req at]
  (verbose-trace at direction "request" (format-request-signature req)
                 (format-params req)))

(defn ^:private verbose-response [direction req resp started finished]
  (verbose-trace finished direction "response"
                 (format-response-header-details req resp started finished)
                 (format-response-body resp)))

(defn ^:private basic-notification [direction notif at]
  (basic-trace at direction "notification" (format-notification-signature notif)))

(defn ^:private basic-request [direction req at]
  (basic-trace at direction "request" (format-request-signature req)))

(defn ^:private basic-response [direction req resp started finished]
  (basic-trace finished direction "response" (format-response-header-details req resp started finished)))

(defprotocol ITracer
  (received-notification [this notif at])
  (received-request [this req at])
  (received-response [this req resp started finished])
  (received-unmatched-response [this resp at])
  (received-unmatched-cancellation-notification [this notif at])
  (sending-notification [this notif at])
  (sending-request [this req at])
  (sending-response [this req resp started finished]))

(defrecord VerboseTracer []
  ITracer
  (received-notification [_this notif at]
    (verbose-notification "Received" notif at))
  (received-request [_this req at]
    (verbose-request "Received" req at))
  (received-response [_this req resp started finished]
    (verbose-response "Received" req resp started finished))
  (received-unmatched-response [_this resp at]
    (verbose-trace at "Received" "response" "for unmatched request:"
                   (format-body "Body" resp)))
  (received-unmatched-cancellation-notification [_this notif at]
    (verbose-trace at "Received" "cancellation notification" (format-unmatched-notif-header-details notif)
                   (format-params notif)))
  (sending-notification [_this notif at]
    (verbose-notification "Sending" notif at))
  (sending-request [_this req at]
    (verbose-request "Sending" req at))
  (sending-response [_this req resp started finished]
    (verbose-response "Sending" req resp started finished)))

(defrecord MessagesTracer []
  ITracer
  (received-notification [_this notif at]
    (basic-notification "Received" notif at))
  (received-request [_this req at]
    (basic-request "Received" req at))
  (received-response [_this req resp started finished]
    (basic-response "Received" req resp started finished))
  (received-unmatched-response [_this _resp at]
    (basic-trace at "Received" "response" "for unmatched request:"))
  (received-unmatched-cancellation-notification [_this notif at]
    (basic-trace at "Received" "cancellation notification" (format-unmatched-notif-header-details notif)))
  (sending-notification [_this notif at]
    (basic-notification "Sending" notif at))
  (sending-request [_this req at]
    (basic-request "Sending" req at))
  (sending-response [_this req resp started finished]
    (basic-response "Sending" req resp started finished)))

(defrecord SilentTracer []
  ITracer
  (received-notification [_this _notif _at])
  (received-request [_this _req _at])
  (received-response [_this _req _resp _started _finished])
  (received-unmatched-response [_this _resp _at])
  (received-unmatched-cancellation-notification [_this _notif _at])
  (sending-notification [_this _notif _at])
  (sending-request [_this _req _at])
  (sending-response [_this _req _resp _started _finished]))

(defn tracer-for-level [trace-level]
  (case trace-level
    "verbose" (VerboseTracer.)
    "messages" (MessagesTracer.)
    (SilentTracer.)))
