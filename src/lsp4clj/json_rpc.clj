(ns lsp4clj.json-rpc
  "Models LSP JSON-RPC as core.async channels of messages (Clojure hashmaps).

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#baseProtocol"
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [lsp4clj.json-rpc.messages :as json-rpc.messages]))

(set! *warn-on-reflection* true)

(defn ^:private read-n-chars [^java.io.InputStream input content-length charset-s]
  (let [buffer (byte-array content-length)]
    (loop [total-read 0]
      (when (< total-read content-length)
        (let [new-read (.read input buffer total-read (- content-length total-read))]
          (when (< new-read 0)
            ;; TODO: return nil instead?
            (throw (java.io.EOFException.)))
          (recur (+ total-read new-read)))))
    (String. ^bytes buffer ^String charset-s)))

(defn ^:private parse-header [line headers]
  (let [[h v] (string/split line #":\s*" 2)]
    (when-not (contains? #{"Content-Length" "Content-Type"} h)
      (throw (ex-info "unexpected header" {:line line})))
    (assoc headers h v)))

(defn ^:private parse-charset [content-type]
  (or (when content-type
        (when-let [[_ charset] (re-find #"; charset=(.*)$" content-type)]
          (when (not= "utf8" charset)
            charset)))
      "utf-8"))

(defn ^:private read-message [input headers keyword-function]
  (try
    (let [content-length (parse-long (get headers "Content-Length"))
          charset-s (parse-charset (get headers "Content-Type"))
          content (read-n-chars input content-length charset-s)]
      (json/parse-string content keyword-function))
    (catch Exception _
      :parse-error)))

(defn kw->camelCaseString
  "Convert keywords to camelCase strings, but preserve capitalization of things
  that are already strings."
  [k]
  (cond-> k (keyword? k) csk/->camelCaseString))

(defn ^:private write-message [msg]
  (let [content (json/generate-string (cske/transform-keys kw->camelCaseString msg))]
    (print (str "Content-Length: " (count (.getBytes content "utf-8")) "\r\n"
                "\r\n"
                content))
    (flush)))

(defn ^:private read-header-line
  "Reads a line of input. Blocks if there are no messages on the input."
  [^java.io.InputStream input]
  (loop [s (java.lang.StringBuilder.)]
    (let [b (.read input)]
      (case b
        -1 ::eof ;; end of stream
        #_lf 10 (str s) ;; finished reading line
        #_cr 13 (recur s) ;; ignore carriage returns
        (do (.append s (char b))
            (recur s))))))

(defn input-stream->input-chan
  "Returns a channel which will yield parsed messages that have been read off
  the `input`. When the input is closed, closes the channel. By default when the
  channel closes, will close the input, but can be determined by `close?`.

  Reads in a thread to avoid blocking a go block thread."
  ([input] (input-stream->input-chan input {}))
  ([^java.io.InputStream input {:keys [close? keyword-function]
                                :or {close? true, keyword-function csk/->kebab-case-keyword}}]
   (let [msgs (async/chan 1)]
     (async/thread
       (loop [headers {}]
         (let [line (read-header-line input)]
           (cond
             ;; input closed; also close channel
             (= line ::eof)       (async/close! msgs)
             ;; a blank line after the headers indicates start of message
             (string/blank? line) (if (async/>!! msgs (read-message input headers keyword-function))
                                    ;; wait for next message
                                    (recur {})
                                    ;; msgs closed
                                    (when close? (.close input)))
             :else                (recur (parse-header line headers))))))
     msgs)))

(defn output-stream->output-chan
  "Returns a channel which expects to have messages put on it. nil values are
  not allowed. Serializes and writes the messages to the output. When the
  channel is closed, closes the output.

  Writes in a thread to avoid blocking a go block thread."
  [^java.io.OutputStream output]
  (let [messages (async/chan 1)]
    (binding [*out* (io/writer output)]
      (async/thread
        (loop []
          (if-let [msg (async/<!! messages)]
            (do
              (write-message msg)
              (recur))
            ;; channel closed; also close output
            (.close output)))))
    messages))
