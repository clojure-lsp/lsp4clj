(ns lsp4clj.io-server
  (:require
   [lsp4clj.server :as server]
   [lsp4clj.json-rpc :as json-rpc]))

(set! *warn-on-reflection* true)

(defn server
  "Starts a chan-server, reading and writing from `:in` and `:out` and
  transferring messages to `:input-ch` and `:output-ch`."
  [{:keys [in out] :as opts}]
  (server/chan-server (assoc opts
                             :input-ch (json-rpc/input-stream->input-chan in)
                             :output-ch (json-rpc/output-stream->output-chan out))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn stdio-server
  "Starts a server reading from stdin and writing to stdout."
  ([] (stdio-server {}))
  ([{:keys [in out] :as opts}]
   (server (assoc opts
                  :in (or in System/in)
                  :out (or out System/out)))))
