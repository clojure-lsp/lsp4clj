(ns lsp4clj.coercer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [lsp4clj.coercer :as coercer]))

(deftest clj->java
  (testing "converting map with keyword and vectors"
    (is (= {"cljfmt" {"indents" {"something" [["block" 1]]}}}
           (coercer/clj->java {:cljfmt {:indents {'something [[:block 1]]}}})))))
