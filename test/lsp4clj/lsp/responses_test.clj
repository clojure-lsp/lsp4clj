(ns lsp4clj.lsp.responses-test
  (:require
   [clojure.test :refer [deftest is]]
   [lsp4clj.lsp.responses :as lsp.responses]))

(deftest infer-test
  ;; defaults to result
  (is (= {:jsonrpc "2.0"
          :id 1
          :result {:response "body"}}
         (-> (lsp.responses/response 1)
             (lsp.responses/infer {:response "body"}))))
  ;; detects error
  (is (= {:jsonrpc "2.0"
          :id 1
          :error {:response "body"}}
         (-> (lsp.responses/response 1)
             (lsp.responses/infer {:error {:response "body"}})))))
