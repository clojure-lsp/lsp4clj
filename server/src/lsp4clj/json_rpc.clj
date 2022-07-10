(ns lsp4clj.json-rpc
  "Models LSP JSON-RPC as core.async channels of messages (Clojure hashmaps).

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#baseProtocol"
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as string]))

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

(defn ^:private read-message [input headers]
  (let [content-length (parse-long (get headers "Content-Length"))
        charset-s (parse-charset (get headers "Content-Type"))
        content (read-n-chars input content-length charset-s)]
    ;; TODO: figure out how to signal errors to lsp4clj.server
    ;; TODO: catch exceptions and return -32700 Parse error
    ;; TODO: validate message conforms to JSON-RPC request object
    ;; (jsonrpc/method/id/params) and return -32600 Invalid Request if not.
    (json/parse-string content csk/->kebab-case-keyword)))

(defn ^:private write-message [msg]
  (let [content (json/generate-string (cske/transform-keys csk/->camelCaseString msg))]
    (print (str "Content-Length: " (count (.getBytes content "utf-8")) "\r\n"
                "\r\n"
                content))
    (flush)))

(defn ^:private read-line-async
  "Reads a line of input asynchronously. Returns a channel which will yield the
  line when it is ready, or nil if the input has closed. Returns immediately.
  Avoids blocking by reading in a separate thread."
  [^java.io.InputStream input]
  (async/thread
    (loop [s (java.lang.StringBuilder.)]
      (let [b (.read input)]
        (case b
          -1 ::eof ;; end of stream
          #_lf 10 (str s) ;; finished reading line
          #_cr 13 (recur s) ;; ignore carriage returns
          (do (.append s (char b))
              (recur s)))))))

(defn input-stream->receiver-chan
  "Returns a channel which will yield parsed messages that have been read off
  the input. When the input is closed, closes the channel."
  [^java.io.InputStream input]
  (let [msgs (async/chan 1)]
    (async/go-loop [headers {}]
      (let [line (async/<! (read-line-async input))]
        (cond
          ;; input closed; also close channel
          (= line ::eof)       (async/close! msgs)
          ;; a blank line after the headers indicates start of message
          (string/blank? line) (do
                                 (async/>! msgs (read-message input headers))
                                 (recur {}))
          :else                (recur (parse-header line headers)))))
    msgs))

(defn output-stream->sender-chan
  "Returns a channel which expects to have messages put on it. nil values are
  not allowed. Serializes and writes the messages to the output. When the
  channel is closed, closes the output."
  [^java.io.OutputStream output]
  (let [messages (async/chan 1)]
    (binding [*out* (io/writer output)]
      (async/go-loop []
        (if-let [msg (async/<! messages)]
          (do
            (write-message msg)
            (recur))
          ;; channel closed; also close output
          (.close output))))
    messages))
