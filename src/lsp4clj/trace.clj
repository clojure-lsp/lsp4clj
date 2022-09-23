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

(defn ^:private format-trace [at direction message-type header-details body]
  [:debug
   (str (format-tag at) " " direction " " message-type " " header-details "\n"
        body "\n\n\n")])

(defn ^:private latency [^java.time.Instant started ^java.time.Instant finished]
  (format "%sms" (- (.toEpochMilli finished) (.toEpochMilli started))))

(defn ^:private format-notification [direction notif at]
  (format-trace at direction "notification" (format-notification-signature notif)
                (format-params notif)))

(defn ^:private format-request [direction req at]
  (format-trace at direction "request" (format-request-signature req)
                (format-params req)))

(defn ^:private format-response [direction req {:keys [error] :as resp} started finished]
  (format-trace finished direction "response"
                (format
                  (str "%s. Request took %s." (when error " Request failed: %s (%s)."))
                  (format-request-signature req)
                  (latency started finished)
                  (:message error) (:code error))
                (format-response-body resp)))

(defn received-notification [notif at] (format-notification "Received" notif at))
(defn received-request [req at] (format-request "Received" req at))
(defn received-response [req resp started finished] (format-response "Received" req resp started finished))

(defn received-unmatched-response [resp at]
  (format-trace at "Received" "response" "for unmatched request:"
                (format-body "Body" resp)))

(defn received-unmatched-cancellation-notification [notif at]
  (format-trace at "Received" "cancellation notification" "for unmatched request:"
                (format-params notif)))

(defn sending-notification [notif at] (format-notification "Sending" notif at))
(defn sending-request [req at] (format-request "Sending" req at))
(defn sending-response [req resp started finished] (format-response "Sending" req resp started finished))
