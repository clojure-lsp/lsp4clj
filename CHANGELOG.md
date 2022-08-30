# Changelog

## Unreleased

- Deprecate and remove `lsp4clj.json-rpc.messages`. Use `lsp4clj.lsp.requests` or `lsp4clj.lsp.responses` instead.
- Fix handling of responses during the process of other requests at the same time.

## v1.1.0

- Support communication through a socket, as an alternative to stdio. #1
- Deprecate `lsp4clj.server/stdio-server`. Use the identical `lsp4clj.io-server/server` or `lsp4clj.io-server/stdio-server` instead.

## v1.0.1

- Fix input coercion of completion items, so they can be roundtripped through `completionItem/resolve`. #15

## v1.0.0

## v1.0.0

- Remove lsp4j, as per https://github.com/clojure-lsp/lsp4clj/issues/8
This is essentially a rewrite of lsp4clj. Users of lsp4clj v0.4.3 and earlier
are encouraged to upgrade. Bug fixes for these earlier versions will be
considered, but the lsp4j-based version of this library will not receive
long-term support. For an example of how to use the new version of the library,
see https://github.com/clojure-lsp/clojure-lsp/pull/1117

## v0.4.3

## v0.4.2

## v0.4.1

- fix: shutdown request to return null instead of empty object

## v0.4.0

- Bump lsp4j to 0.14.0

## v0.3.0

- Improve logging of exceptions.

## v0.2.0

- Support LSP 3.16 file operations: `workspace/willRenameFiles`, `workspace/didRenameFiles`, 
`workspace/willCreateFiles`, `workspace/didCreateFiles`, `workspace/willDeleteFiles`, `workspace/didDeleteFiles`.

## v0.0.1

- First release
