(ns lsp4clj.trace
  (:require
   [cheshire.core :as json]))

(defn ^:private trace-header
  ([at description method]
   (format "[Trace - %s] %s '%s'"
           (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))
           description
           method))
  ([at description method id]
   (format "[Trace - %s] %s '%s - (%s)'"
           (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))
           description
           method
           id)))

(defn ^:private trace-body
  ([params] (trace-body "Params" params))
  ([label params] (str label ": " (json/generate-string params {:pretty true}))))

(defn ^:private trace-str
  ([header latency body]
   (trace-str (str header latency) body))
  ([header body]
   (str header "\n"
        body "\n\n\n")))

(defn ^:private latency [started finished]
  (- (.toEpochMilli finished) (.toEpochMilli started)))

(defn received-notification [method params at]
  (trace-str (trace-header at "Received notification" method)
             (trace-body params)))

(defn received-request [id method params at]
  (trace-str (trace-header at "Received request" method id)
             (trace-body params)))

(defn received-response [id method result error started finished]
  (trace-str (trace-header finished "Received response" method id)
             (str (format " in %sms." (latency started finished))
                  (when error (format " Request failed: %s (%s)." (:message error) (:code error))))
             (if error (trace-body "Error data" (:data error)) (trace-body "Result" result))))

(defn sending-notification [method params at]
  (trace-str (trace-header at "Sending notification" method)
             (trace-body params)))

(defn sending-request [id method params at]
  (trace-str (trace-header at "Sending request" method id)
             (trace-body params)))

(defn sending-response [id method params started finished]
  (trace-str (trace-header finished "Sending response" method id)
             (format ". Processing request took %sms" (latency started finished))
             (trace-body "Result" params)))
