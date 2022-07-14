(ns lsp4clj.protocols.endpoint)

(defprotocol IEndpoint
  (start [this context])
  (shutdown [this])
  (exit [this])
  (log [this level arg1] [this level arg1 arg2])
  (send-request [this method body])
  (send-notification [this method body])
  (receive-response [this resp])
  (receive-request [this context req])
  (receive-notification [this context notif]))
