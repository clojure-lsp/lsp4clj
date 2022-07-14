(ns lsp4clj.liveness-probe
  (:require
   [clojure.core.async :as async]
   [clojure.java.shell :as shell]
   [clojure.string :as string]))

(defn ^:private windows-process-alive?
  [pid]
  (let [{:keys [out]} (shell/sh "tasklist" "/fi" (format "\"pid eq %s\"" pid))]
    (string/includes? out (str pid))))

(defn ^:private unix-process-alive?
  [pid]
  (let [{:keys [exit]} (shell/sh "kill" "-0" (str pid))]
    (zero? exit)))

(defn ^:private process-alive?
  [pid log]
  (try
    (if (.contains (System/getProperty "os.name") "Windows")
      (windows-process-alive? pid)
      (unix-process-alive? pid))
    (catch Exception e
      (log :warn e "Liveness probe - Checking if process is alive failed.")
      true)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn start!
  [ppid log on-exit]
  (async/go-loop []
    (async/<! (async/timeout 5000))
    (if (process-alive? ppid log)
      (recur)
      (do
        (log :info (str "Liveness probe - Parent process " ppid " is not running - exiting server"))
        (on-exit)))))
