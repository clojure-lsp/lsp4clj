(ns lsp4clj.lsp.requests)

(set! *warn-on-reflection* true)

(defn request [id method params]
  {:jsonrpc "2.0"
   :method method
   :params params
   :id id})

(defn notification [method params]
  {:jsonrpc "2.0"
   :method method
   :params params})

;; TODO: The following are helpers used by servers, but not by lsp4clj itself.
;; Perhaps they belong in a utils namespace.

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn work-done-progress
  "Returns the params for a WorkDone $/progress notification.

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workDoneProgress
  "
  [percentage message progress-token]
  (let [percentage (int percentage)
        progress {:kind (case percentage
                          0 :begin
                          100 :end
                          :report)
                  :title message
                  :percentage percentage}]
    {:token (or progress-token "lsp4clj")
     :value progress}))
