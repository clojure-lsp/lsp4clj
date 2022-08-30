(ns lsp4clj.lsp.responses
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]))

(set! *warn-on-reflection* true)

(defn response
  "Create a JSON-RPC response object. A response should be extend with a
  `:result` or `:error` either by using the arity-2 version, or via the helpers
  `result`, `error`, or `infer`.

  https://www.jsonrpc.org/specification#response_object"
  ([id]
   {:jsonrpc "2.0"
    :id id})
  ([id body]
   {:jsonrpc "2.0"
    :id id
    :result body}))

(defn result
  "Indicate that a JSON-RPC response was successful by adding a result to it."
  [response body]
  (-> response
      (dissoc :error)
      (assoc :result body)))

(defn error
  "Indicate that a JSON-RPC response was an error by adding a error to it.

  The body should conform to the JSON-RPC spec for error objects, which can be
  constructed with the helpers in `lsp4clj.lsp.errors`."
  [response body]
  (-> response
      (dissoc :result)
      (assoc :error body)))

(defn infer
  "Infer whether to update the response with `result` or `error` depending on
  the shape of the `body`.

  If `body` is a map containing the key `:error`, uses the value as the `error`
  body. Otherwise uses `body` as the `result`."
  [response body]
  (if (and (map? body) (:error body))
    (error response (:error body))
    (result response body)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn preserve-kebab-case
  "Recursively convert map keywords to kebab-case strings, to avoid automatic
  camelCase conversion. This is useful for servers when the client expects
  Clojure style JSON, or when a map needs to be round-tripped from the server to
  the client and back without case changes."
  [m]
  (cske/transform-keys csk/->kebab-case-string m))
