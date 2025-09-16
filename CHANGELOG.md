# Changelog

## Unreleased

- bump jsonrpc4clj

## v2.0.0

- Move most logic to jsonrpc4clj library.

## v1.13.1

- Fix coercers

## v1.13.0

- Add textDocument/selectionRange LSP feature coercers.

## v1.12.0

- Add inlay-hint LSP feature coercers.

## v1.11.0

- Add a `:response-executor` option to control on which thread responses to
  server-initiated requests are run, defaulting to Promesa's `:default`
  executor, i.e. `ForkJoinPool/commonPool`.
- Fix work done progress notification to allow nullable `message`.

## v1.10.0

- Add `textDocument/foldingRange` schemas.

## v1.9.3

## v1.9.2

## v1.9.1

## v1.9.0

- In certain situations the server will abort its pending sent requests to avoid
  blocking the client's messages.
- Server requests can be treated as promesa promises.
- Bump promesa to `10.0.594`

## v1.8.1

- Bump promesa to `10.0.571`

## v1.8.0

- Allow create work-done-progress without percentages, for report only.

## v1.7.4

- Deprecate `lsp4clj.socket-server` and document preferred alternative in the README.

## v1.7.3

- Server continues receiving responses while it is blocking requests or notifications.

## v1.7.2

## v1.7.1

- Fix $/progress notifications when kind is report to pass message instead of title.

## v1.7.0

- Add :coercer/any-or-error for execute-command response

## v1.6.0

- Buffer I/O, slightly reducing latency.

## v1.5.0

- Let language servers abort running requests that a client has cancelled.

## v1.4.0

- Let language servers pick detail of traces, by setting `:trace-level`. #27
- Let language servers set `:trace-level` on running lsp4clj server. #27

## v1.3.1

## v1.3.0

- Allow language servers to process and respond to requests in parallel.

## v1.2.2

## v1.2.1

- Split server and chan related ns into their own ns.

## v1.2.0

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
