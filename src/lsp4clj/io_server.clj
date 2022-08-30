(ns lsp4clj.io-server
  (:require
   [lsp4clj.io-chan :as io-chan]
   [lsp4clj.server :as server]))

(set! *warn-on-reflection* true)

;;;; Create server

(defn server
  "Starts a chan-server, reading messages from `:in` and putting them on
  `:input-ch`, and taking messages from `:output-ch` and writing them to
  `:out`."
  [{:keys [in out] :as opts}]
  (server/chan-server (assoc opts
                             :input-ch (io-chan/input-stream->input-chan in)
                             :output-ch (io-chan/output-stream->output-chan out))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn stdio-server
  "Starts a server reading from stdin and writing to stdout."
  ([] (stdio-server {}))
  ([{:keys [in out] :as opts}]
   (server (assoc opts
                  :in (or in System/in)
                  :out (or out System/out)))))
