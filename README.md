# lsp4clj

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) base for developing any LSP implementation in Clojure.

## Server

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-lsp/lsp4clj-server.svg)](https://clojars.org/com.github.clojure-lsp/lsp4clj-server)

The lsp4clj-server has the necessary integration with Input/Output, LSP-JSON parsing, allowing for users of this lib to just code the entrypoints of each LSP method.

## Protocols

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-lsp/lsp4clj-protocols.svg)](https://clojars.org/com.github.clojure-lsp/lsp4clj-protocols)

The lsp4clj-protocols contains only the protocols/interfaces for servers that want to extend the official LSP protocol to provide more features, also, it is used by lsp4clj-server itself.

## Known LSPs users

- [clojure-lsp](https://clojure-lsp.io/): A Clojure LSP server implementation.
