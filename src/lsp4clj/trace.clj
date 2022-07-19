(ns lsp4clj.trace
  (:require
   [cheshire.core :as json]))

(set! *warn-on-reflection* true)

(defn ^:private format-tag [^java.time.Instant at]
  (format "[Trace - %s]"
          (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))))

(defn ^:private format-header
  ([description method]
   (format "%s '%s'" description method))
  ([description method id]
   (format "%s '%s - (%s)'" description method id)))

(defn ^:private format-error-header [error]
  (format "Request failed: %s (%s)." (:message error) (:code error)))

(defn ^:private format-body
  ([params] (format-body "Params" params))
  ([label params] (str label ": " (json/generate-string params {:pretty true}))))

(defn ^:private format-error-body [error]
  (format-body "Error data" (:data error)))

(defn ^:private format-str
  ([tag header extra-header body]
   (format-str tag (str header extra-header) body))
  ([tag header body]
   (str tag " " header "\n"
        body "\n\n\n")))

(defn ^:private latency [^java.time.Instant started ^java.time.Instant finished]
  (- (.toEpochMilli finished) (.toEpochMilli started)))

(defn received-notification [{:keys [method params]} at]
  (format-str (format-tag at)
              (format-header "Received notification" method)
              (format-body params)))

(defn received-request [{:keys [id method params]} at]
  (format-str (format-tag at)
              (format-header "Received request" method id)
              (format-body params)))

(defn received-response [{:keys [id method] :as _req} {:keys [result error] :as _resp} started finished]
  (format-str (format-tag finished)
              (format-header "Received response" method id)
              (str (format " in %sms." (latency started finished))
                   (when error (str " " (format-error-header error))))
              (if error (format-error-body error) (format-body "Result" result))))

(defn received-unmatched-response [resp at]
  (format-str (format-tag at)
              "Received response for unmatched request:"
              (format-body "Body" resp)))

(defn sending-notification [{:keys [method params]} at]
  (format-str (format-tag at)
              (format-header "Sending notification" method)
              (format-body params)))

(defn sending-request [{:keys [id method params]} at]
  (format-str (format-tag at)
              (format-header "Sending request" method id)
              (format-body params)))

(defn sending-response [{:keys [id method]} {:keys [result error]} started finished]
  (format-str (format-tag finished)
              (format-header "Sending response" method id)
              (str (format ". Processing request took %sms" (latency started finished))
                   (when error (str ". " (format-error-header error))))
              (if error (format-error-body error) (format-body "Result" result))))
