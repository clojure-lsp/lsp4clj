(ns lsp4clj.trace
  (:require
   [cheshire.core :as json]))

(set! *warn-on-reflection* true)

(defn ^:private trace-tag [^java.time.Instant at]
  (format "[Trace - %s]"
          (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))))

(defn ^:private trace-header
  ([description method]
   (format "%s '%s'" description method))
  ([description method id]
   (format "%s '%s - (%s)'" description method id)))

(defn ^:private trace-body
  ([params] (trace-body "Params" params))
  ([label params] (str label ": " (json/generate-string params {:pretty true}))))

(defn ^:private trace-str
  ([tag header latency body]
   (trace-str tag (str header latency) body))
  ([tag header body]
   (str tag " " header "\n"
        body "\n\n\n")))

(defn ^:private latency [^java.time.Instant started ^java.time.Instant finished]
  (- (.toEpochMilli finished) (.toEpochMilli started)))

(defn received-notification [method params at]
  (trace-str (trace-tag at)
             (trace-header "Received notification" method)
             (trace-body params)))

(defn received-request [id method params at]
  (trace-str (trace-tag at)
             (trace-header "Received request" method id)
             (trace-body params)))

(defn received-response [id method result error started finished]
  (trace-str (trace-tag finished)
             (trace-header "Received response" method id)
             (str (format " in %sms." (latency started finished))
                  (when error (format " Request failed: %s (%s)." (:message error) (:code error))))
             (if error (trace-body "Error data" (:data error)) (trace-body "Result" result))))

(defn received-unmatched-response [at resp]
  (trace-str (trace-tag at)
             "Received response for unmatched request:"
             (trace-body "Body" resp)))

(defn sending-notification [method params at]
  (trace-str (trace-tag at)
             (trace-header "Sending notification" method)
             (trace-body params)))

(defn sending-request [id method params at]
  (trace-str (trace-tag at)
             (trace-header "Sending request" method id)
             (trace-body params)))

(defn sending-response [id method params started finished]
  (trace-str (trace-tag finished)
             (trace-header "Sending response" method id)
             (format ". Processing request took %sms" (latency started finished))
             (trace-body "Result" params)))
