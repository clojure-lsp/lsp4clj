(ns lsp4clj.test-helper
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [is]]))

(defn take-or-timeout
  ([ch]
   (take-or-timeout ch 100))
  ([ch timeout-ms]
   (take-or-timeout ch timeout-ms :timeout))
  ([ch timeout-ms timeout-val]
   (let [timeout (async/timeout timeout-ms)
         [result ch] (async/alts!! [ch timeout])]
     (if (= ch timeout)
       timeout-val
       result))))

(defn assert-no-take [ch]
  (is (= :nothing (take-or-timeout ch 500 :nothing))))

(defn assert-take [ch]
  (let [result (take-or-timeout ch)]
    (is (not= :timeout result))
    result))

(defn put-or-timeout
  ([ch message]
   (put-or-timeout ch message 100))
  ([ch message timeout-ms]
   (put-or-timeout ch message timeout-ms :timeout))
  ([ch message timeout-ms timeout-val]
   (let [timeout (async/timeout timeout-ms)
         [result ch] (async/alts!! [[ch message] timeout])]
     (if (= ch timeout)
       timeout-val
       result))))

(defn assert-put [ch message]
  (is (not= :timeout (put-or-timeout ch message))))

(defn assert-deref [p]
  (let [result (deref p 200 :timeout)]
    (is (not= :timeout result))
    result))
