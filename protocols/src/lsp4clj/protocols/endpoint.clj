(ns lsp4clj.protocols.endpoint)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defprotocol IEndpoint
  (start [this])
  (shutdown [this])
  (exit [this])
  (send-request [this method body])
  (send-notification [this method body])
  (receive-response [this resp])
  (receive-request [this req])
  (receive-notification [this notif]))