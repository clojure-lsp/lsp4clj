(ns lsp4clj.trace
  (:require
   [cheshire.core :as json]))

(set! *warn-on-reflection* true)

(defn ^:private format-tag [^java.time.Instant at]
  (format "[Trace - %s]"
          (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))))

(defn ^:private format-request [{:keys [method id]}]
  (format "request '%s - (%s)'" method id))

(defn ^:private format-response-header [{:keys [method id]}]
  (format "response '%s - (%s)'" method id))

(defn ^:private format-notification [{:keys [method]}]
  (format "notification '%s'" method))

(defn ^:private format-error [error]
  (format "Request failed: %s (%s)." (:message error) (:code error)))

(defn ^:private format-body [label body]
  (str label ": " (json/generate-string body {:pretty true})))

(defn ^:private format-params [{:keys [params]}]
  (format-body "Params" params))

(defn ^:private format-response-body [{:keys [error result]}]
  (if error
    (format-body "Error data" (:data error))
    (format-body "Result" result)))

(defn ^:private format-str
  ([at action header extra-header body]
   (format-str at action (str header extra-header) body))
  ([at action header body]
   (str (format-tag at) " " action " " header "\n"
        body "\n\n\n")))

(defn ^:private latency [^java.time.Instant started ^java.time.Instant finished]
  (format "%sms" (- (.toEpochMilli finished) (.toEpochMilli started))))

(defn received-notification [notif at]
  (format-str at "Received" (format-notification notif)
              (format-params notif)))

(defn received-request [req at]
  (format-str at "Received" (format-request req)
              (format-params req)))

(defn received-response [req {:keys [error] :as resp} started finished]
  (format-str finished "Received" (format-response-header req)
              (str (format " in %s." (latency started finished))
                   (when error (str " " (format-error error))))
              (format-response-body resp)))

(defn received-unmatched-response [resp at]
  (format-str at "Received" "response for unmatched request:"
              (format-body "Body" resp)))

(defn sending-notification [notif at]
  (format-str at "Sending" (format-notification notif)
              (format-params notif)))

(defn sending-request [req at]
  (format-str at "Sending" (format-request req)
              (format-params req)))

(defn sending-response [req {:keys [error] :as resp} started finished]
  (format-str finished "Sending" (format-response-header req)
              (str (format ". Processing request took %s" (latency started finished))
                   (when error (str ". " (format-error error))))
              (format-response-body resp)))
