# lsp4clj

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) base for developing any LSP implementation in Clojure.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-lsp/lsp4clj.svg)](https://clojars.org/com.github.clojure-lsp/lsp4clj)

lsp4clj reads and writes from stdio, parsing JSON-RPC according to the LSP spec. It provides tools to allow server implementors to respond to any of the methods defined in the LSP spec, and to send requests and notifications.

## Known LSPs users

- [clojure-lsp](https://clojure-lsp.io/): A Clojure LSP server implementation.
