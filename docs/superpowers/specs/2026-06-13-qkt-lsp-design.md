# qkt Language Server — Design

Date: 2026-06-13
Status: Approved, in implementation (branch `feat-lsp` off `dev`)

## Goal

An editor-agnostic Language Server for `.qkt` files so any LSP-capable editor
(Neovim, VS Code, Helix, Emacs, Zed, Sublime) gets live feedback. The protocol is
the point: write the intelligence once, reach every editor.

v1 features: **diagnostics**, **completion**, **hover**. Deferred (need source spans
on AST nodes, a larger qkt-core change): go-to-definition, references, rename,
formatting, document outline, signature help, semantic highlighting. Out of scope:
web/WASM editors (the server is a JVM process).

## Core principle: reuse qkt's front-end

The server calls qkt's own lexer/parser/registries, so editor feedback is identical
to what `qkt parse` / `qkt run` accept, with zero grammar duplication:

- `com.qkt.dsl.parse.Dsl.parseAny(source)` -> `ParseResult` (`Success(ast)` |
  `Failure(errors)`); the parser collects every error before bailing.
- `ParseError(line, col, message)` and `Token(kind, lexeme, line, col)` — 1-based.
  `col` counts Kotlin `String` (UTF-16) units, which matches LSP's default position
  encoding, so `lspChar = col - 1` is exact.
- `IndicatorRegistry` (name -> `IndicatorSpec`: arity, series count, input kind),
  `FuncRegistry`, `Constants`, and the lexer keyword set drive completion + hover.

Two robustness facts the implementation must respect:

1. `Lexer.tokenize()` **throws** (`IllegalStateException`) on malformed input
   (unterminated string, stray char), and `Dsl.parse*` does not catch it — so
   `Dsl.parseAny` can throw. The diagnostics path and all re-lexing wrap calls in
   `try/catch(Throwable)` and recover a position from the lexer message when present.
2. AST nodes carry **no** source positions (only tokens and parse errors do).
   Completion and hover therefore work at the **token** level (re-lex, cheap — qkt
   files are small), and symbol aliases / `LET` / `PARAM` names come from the last
   successfully parsed AST, kept per-document so they survive mid-edit breakage.

## Architecture

A package `com.qkt.lsp` in qkt's main module, exposed as a CLI subcommand:

```
qkt lsp        # speaks LSP over stdin/stdout
```

A package + subcommand (not a separate Gradle module) because the `qkt lsp`
entrypoint is a CLI subcommand and the server needs the parser — both live in the
app module. A separate `:lsp` module depending on the parser would cycle with the
CLI unless qkt were split into `:core`/`:app`/`:lsp` (invasive, YAGNI now). Revisit
only if bundling LSP4J in the core jar becomes a problem.

Library: Eclipse LSP4J `0.23.1` (`org.eclipse.lsp4j:org.eclipse.lsp4j`).

| Component | Responsibility |
|---|---|
| `LspCommand` (`com.qkt.cli`) | Wire `qkt lsp` into `Main.runMain`; start the server on stdio. |
| `QktLanguageServer` | `initialize` (advertise capabilities), `shutdown`, `exit`. |
| `QktTextDocumentService` | `didOpen`/`didChange`/`didClose`, `completion`, `hover`. |
| `DocumentStore` | `uri -> (text, lastGoodAst)`. Full-text sync. |
| `Diagnostics` | Parse text; map `ParseError` -> LSP `Diagnostic` with ranges. |
| `Completion` | Token-based context classify; emit `CompletionItem`s. |
| `Hover` | Resolve token under cursor to doc text. |
| `QktVocabulary` | The single seam onto qkt: keyword list + registry data + curated docs. |

## The only qkt-core touches (additive, read-only)

- `IndicatorRegistry.names()` (+ existing `spec`), `FuncRegistry.names()`,
  `Constants.names()` — enumerate the private tables for completion.
- Expose the lexer keyword spellings (the private `KEYWORDS` keys) so completion does
  not duplicate the lexer's keyword/operator exclusion list.
- Hover prose lives in a curated map inside the LSP (sourced from
  `docs/reference/dsl/*.md`); a drift-guard test asserts every registry name has a
  doc and is reachable from completion.

## Position handling

- Diagnostics: `ParseError(line, col)` (1-based point) -> 0-based LSP range; size the
  squiggle by re-lexing and matching the token at that point (`lexeme` length),
  falling back to a 1-char range or end-of-line. Lexer exceptions -> single
  best-effort diagnostic parsed from the message.
- Completion/hover: re-lex (catching exceptions); reason about the token at/just
  before the cursor; pull aliases and `LET`/`PARAM` names from `lastGoodAst`.

## Completion contexts (v1, heuristic)

- Top level: section keywords (`STRATEGY`, `DEFAULTS`, `SYMBOLS`, `LET`, `PARAM`,
  `RULES`, `SCHEDULE`, `PORTFOLIO`).
- Expression position: indicators, functions, constants, symbol aliases,
  `LET`/`PARAM` names, accessor roots (`POSITION`/`ACCOUNT`/`NOW`), expression
  keywords/operators (`CROSSES`, `ABOVE`, `BELOW`, `BETWEEN`, `IS`, `NULL`, `AND`,
  `OR`, `NOT`).
- After `<alias>.` -> stream fields (`close`, `open`, `high`, `low`, `volume`, `tick`).
- After `POSITION.` / `NOW.` / `ACCOUNT.` -> that accessor's members.
- Trigger characters: `.` and `(`.

## Hover (v1)

Indicator -> signature (arity/series/input kind) + curated description + example.
Function -> signature + description. Keyword -> one-line help. Constant -> value.

## Error handling

- Publish all collected errors. Mid-edit unparseable docs never break the session:
  degrade to `lastGoodAst` + token scan; return empty results, never throw to client.
- **stdout is the protocol channel.** All logging goes to stderr/file; the LSP must
  never write to stdout outside the protocol. Covered by a test.

## Testing (real data, no mocks)

- Diagnostics: real `Dsl.parseAny` over valid/broken `.qkt` (reuse
  `src/test/resources/cli/{valid,broken}_strategy.qkt` + crafted cases); assert
  ranges and messages.
- Completion/hover: `(document, position)` -> assert candidate sets / hover text.
- Drift guard: every registry name has a doc and appears in completion.
- End-to-end: start the real server via `LSPLauncher` over in-memory pipes; drive
  `initialize` -> `didOpen` -> diagnostics/completion/hover with real JSON-RPC.
  **Untagged** so it runs in the default `./gradlew test` (the `e2e` tag is excluded
  by the build and reserved for heavy/external suites; this one is in-process/fast).
- Pristine output: assert no stray stdout.

## Distribution & install

`qkt lsp` ships inside the qkt distribution (bundled-JRE tarball, Docker,
`installDist`) and is on `PATH`, so having qkt means having the server. Per-editor
glue is small and installed by the existing `qkt editor install`:

- Neovim: `ftplugin/qkt.vim` autostarts `vim.lsp.start{ cmd={"qkt","lsp"} }`
  (installer already ships this file).
- Helix / Emacs (eglot) / Zed / VS Code: documented one-line config pointing at
  `qkt lsp`.

## Phasing

P0 skeleton (`qkt lsp` + lifecycle + DocumentStore) -> P1 diagnostics -> P2
completion -> P3 hover -> E2E -> editor glue + docs. Each phase tested before the next.

## Version control

Branch `feat-lsp` off `origin/dev` (local `dev` was 21 commits stale). Merge target
`dev`; promotion to `main` and any remote push confirmed with the author (qkt's
remote has release automation).
