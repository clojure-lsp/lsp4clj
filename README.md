# lsp4clj

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) base for developing any LSP implementation in Clojure.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-lsp/lsp4clj.svg)](https://clojars.org/com.github.clojure-lsp/lsp4clj)

Currently lsp4clj is just a wrapper for [jsonrpc4clj](https://github.com/clojure-lsp/jsonrpc4clj) adding some LSP schemas in `lsp4clj.coercer`, for more details how to use it check that lib.

## Known lsp4clj users

- [clojure-lsp](https://clojure-lsp.io/): A Clojure LSP server implementation.
- [mcp-clojure-sdk](https://github.com/unravel-team/mcp-clojure-sdk): A Clojure MCP SDK for writing MCP servers.

## Release

To release a new version, run `bb tag x.y.z`, it should do all necessary changes and trigger a Github Action to deploy to clojars the new version.
