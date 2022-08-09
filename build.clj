(ns build
  (:require
   [clojure.string :as string]
   [clojure.tools.build.api :as b]))

(def lib 'com.github.clojure-lsp/lsp4clj)
(def jar-file (format "target/%s.jar" (name lib)))

(def current-version (string/trim (slurp "resources/LSP4CLJ_VERSION")))

(defn clean [_]
  (b/delete {:path "target"}))

(defn pom [opts]
  (clean opts)
  (b/write-pom {:target ""
                :lib lib
                :version current-version
                :basis (b/create-basis {:project "deps.edn"})
                :src-dirs ["src"]
                :resource-dirs ["resources"]
                :scm {:tag (str "v" current-version)}}))

(defn jar [opts]
  (pom opts)
  (b/copy-dir {:src-dirs ["src"]
               :target-dir "target/classes"})
  (b/jar {:class-dir "target/classes"
          :jar-file jar-file}))

(defn deploy-clojars [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact jar-file
           :pom-file "pom.xml"}
          opts))
  opts)
