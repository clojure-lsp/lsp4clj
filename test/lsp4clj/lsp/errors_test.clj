(ns lsp4clj.lsp.errors-test
  (:require
   [clojure.test :refer [deftest is]]
   [lsp4clj.lsp.errors :as lsp.errors]))

(deftest body-should-look-up-error-by-code
  ;; Uses known code and message
  (is (= {:code -32001
          :message "Unknown error"}
         (lsp.errors/body :unknown-error-code nil nil)))
  ;; Prefers custom message with known code
  (is (= {:code -32001
          :message "Custom message"}
         (lsp.errors/body :unknown-error-code "Custom message" nil)))
  ;; Uses custom code
  (is (= {:code -1}
         (lsp.errors/body -1 nil nil)))
  ;; Uses custom code and message
  (is (= {:code -1
          :message "Custom message"}
         (lsp.errors/body -1 "Custom message" nil)))
  ;; Uses custom code, message, and data
  (is (= {:code -1
          :message "Custom message"
          :data {:details "error"}}
         (lsp.errors/body -1 "Custom message" {:details "error"}))))
