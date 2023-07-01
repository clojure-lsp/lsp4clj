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

(defn clamp [n n-min n-max]
  (-> n (max n-min) (min n-max)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn work-done-progress
  "Returns the params for a WorkDone $/progress notification.

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workDoneProgress
  "
  ([message progress-token]
   (work-done-progress nil message progress-token))
  ([percentage message progress-token]
   (when progress-token
     (let [percentage (when percentage (int (clamp percentage 0 100)))
           progress (cond
                      ;; TODO: this is a bit restricting. Technically the 'begin'
                      ;; message can start at a higher `percentage`, and it can
                      ;; have a `message`. To work around this, it's possible to
                      ;; publish a 'begin' immediately followed by a 'progress'
                      ;; with the desired percentage and message.
                      (= 0 percentage) {:kind :begin
                                        :title message
                                        :percentage 0}
                      (= 100 percentage) {:kind :end
                                          :message message}
                      :else
                      (cond->
                       {:kind :report
                        :message message}
                        percentage (assoc :percentage percentage)))]
       {:token progress-token
        :value progress}))))
