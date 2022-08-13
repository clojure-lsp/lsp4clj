(ns lsp4clj.json-rpc
  "Models LSP JSON-RPC as core.async channels of messages (Clojure hashmaps).

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#baseProtocol"
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as string])
  (:import
   [java.io EOFException InputStream OutputStream]
   [java.net InetAddress ServerSocket SocketException]))

(set! *warn-on-reflection* true)

(defn ^:private read-n-bytes [^InputStream input content-length charset-s]
  (let [buffer (byte-array content-length)]
    (loop [total-read 0]
      (when (< total-read content-length)
        (let [new-read (.read input buffer total-read (- content-length total-read))]
          (when (< new-read 0)
            ;; TODO: return nil instead?
            (throw (EOFException.)))
          (recur (+ total-read new-read)))))
    (String. ^bytes buffer ^String charset-s)))

(defn ^:private parse-header [line headers]
  (let [[h v] (string/split line #":\s*" 2)]
    (assoc headers h v)))

(defn ^:private parse-charset [content-type]
  (or (when content-type
        (when-let [[_ charset] (re-find #"(?i)charset=(.*)$" content-type)]
          (when (not= "utf8" charset)
            charset)))
      "utf-8"))

(defn ^:private read-message [input headers keyword-function]
  (try
    (let [content-length (parse-long (get headers "Content-Length"))
          charset-s (parse-charset (get headers "Content-Type"))
          content (read-n-bytes input content-length charset-s)]
      (json/parse-string content keyword-function))
    (catch Exception _
      :parse-error)))

(defn ^:private kw->camelCaseString
  "Convert keywords to camelCase strings, but preserve capitalization of things
  that are already strings."
  [k]
  (cond-> k (keyword? k) csk/->camelCaseString))

(def ^:private write-lock (Object.))

(defn ^:private write-message [^OutputStream output msg]
  (let [content (json/generate-string (cske/transform-keys kw->camelCaseString msg))
        content-bytes (.getBytes content "utf-8")]
    (locking write-lock
      (doto output
        (.write (-> (str "Content-Length: " (count content-bytes) "\r\n"
                         "\r\n")
                    (.getBytes "US-ASCII"))) ;; headers are in ASCII, not UTF-8
        (.write content-bytes)
        (.flush)))))

(defn ^:private read-header-line
  "Reads a line of input. Blocks if there are no messages on the input."
  [^InputStream input]
  (let [s (java.lang.StringBuilder.)]
    (loop []
      (let [b (.read input)] ;; blocks, presumably waiting for next message
        (case b
          -1 ::eof ;; end of stream
          #_lf 10 (str s) ;; finished reading line
          #_cr 13 (recur) ;; ignore carriage returns
          (do (.append s (char b)) ;; byte == char because header is in US-ASCII
              (recur)))))))

(defn input-stream->input-chan
  "Returns a channel which will yield parsed messages that have been read off
  the `input`. When the input is closed, closes the channel. By default when the
  channel closes, will close the input, but can be determined by `close?`.

  Reads in a thread to avoid blocking a go block thread."
  ([input] (input-stream->input-chan input {}))
  ([^InputStream input {:keys [close? keyword-function]
                        :or {close? true, keyword-function csk/->kebab-case-keyword}}]
   (let [messages (async/chan 1)]
     (async/thread
       (loop [headers {}]
         (let [line (read-header-line input)]
           (cond
             ;; input closed; also close channel
             (= line ::eof) (async/close! messages)
             ;; a blank line after the headers indicates start of message
             (string/blank? line) (if (async/>!! messages (read-message input headers keyword-function))
                                    ;; wait for next message
                                    (recur {})
                                    ;; messages closed
                                    (when close? (.close input)))
             :else (recur (parse-header line headers))))))
     messages)))

(defn output-stream->output-chan
  "Returns a channel which expects to have messages put on it. nil values are
  not allowed. Serializes and writes the messages to the output. When the
  channel is closed, closes the output.

  Writes in a thread to avoid blocking a go block thread."
  [^OutputStream output]
  (let [messages (async/chan 1)]
    (async/thread
      (with-open [writer output] ;; close output when channel closes
        (loop []
          (when-let [msg (async/<!! messages)]
            (write-message writer msg)
            (recur)))))
    messages))

(defn start-socket-server
  "Start a socket server, given the specified opts:
    :address Host or address, string, defaults to loopback address
    :port Port, integer, required

  Returns a map containing the :socket-server and :on-connect, which can be
  dereffed. When a client establishes a connection, the deref will resolve to
  the connection and two channels, an input-chan and an output-chan.

  The caller is responsible for closing the socket-server, the connection, and
  the channels."
  [{:keys [address port]}]
  (let [address (InetAddress/getByName address) ;; nil address returns loopback
        server (ServerSocket. port 0 address)] ;; bind to the port
    {:socket-server server
     :on-connect (future
                   (try
                     (let [conn (.accept server)] ;; block waiting for connection
                       [conn
                        (input-stream->input-chan (.getInputStream conn))
                        (output-stream->output-chan (.getOutputStream conn))])
                     (catch SocketException _disconnect)))}))
