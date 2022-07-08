(ns lsp4clj.liveness-probe
  (:require
   [clojure.core.async :as async]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [lsp4clj.protocols.logger :as logger]))

(defn ^:private windows-process-alive?
  [pid]
  (let [{:keys [out]} (shell/sh "tasklist" "/fi" (format "\"pid eq %s\"" pid))]
    (string/includes? out (str pid))))

(defn ^:private unix-process-alive?
  [pid]
  (let [{:keys [exit]} (shell/sh "kill" "-0" (str pid))]
    (zero? exit)))

(defn ^:private process-alive?
  [pid]
  (try
    (if (.contains (System/getProperty "os.name") "Windows")
      (windows-process-alive? pid)
      (unix-process-alive? pid))
    (catch Exception e
      (logger/warn "Liveness probe - Checking if process is alive failed." e)
      true)))

(defn start!
  [ppid on-exit]
  (async/go-loop []
    (async/<! (async/timeout 5000))
    (if (process-alive? ppid)
      (recur)
      (do
        (logger/info (str "Liveness probe - Parent process " ppid " is not running - exiting server"))
        (on-exit)))))
