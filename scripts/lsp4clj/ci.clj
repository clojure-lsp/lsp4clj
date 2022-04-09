(ns lsp4clj.ci
  (:require
   [babashka.tasks :refer [shell]]
   [clojure.string :as string]))

(defn ^:private replace-in-file [file regex content]
  (as-> (slurp file) $
    (string/replace $ regex content)
    (spit file $)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn tag [& [tag]]
  (shell "git fetch origin")
  (shell "git pull origin HEAD")
  (spit "resources/LSP4CLJ_VERSION" tag)
  (replace-in-file "CHANGELOG.md"
                   #"## Unreleased"
                   (format "## Unreleased\n\n## v%s" tag))
  (shell "git add resources/LSP4CLJ_VERSION CHANGELOG.md")
  (shell (format "git commit -m \"Release: v%s\"" tag))
  (shell (str "git tag v" tag))
  (shell "git push origin HEAD")
  (shell "git push origin --tags"))
