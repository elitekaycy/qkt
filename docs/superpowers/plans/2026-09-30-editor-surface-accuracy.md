# Editor surface accuracy — plan

Spec: `docs/superpowers/specs/2026-09-30-editor-surface-accuracy-design.md`. Four workstreams
run on their own branches from dev 3e699f4f and merge into `feat-editor-surface-accuracy`;
the integration branch then takes one full build, one PR, promotion and attestation. The
backtester follows in its own repo once the qkt image carrying this lands.

## W1 — diagnostics run the compiler (dsl/compile, dsl/parse/RuleParser, lsp/DiagnosticsRunner, cli/ParseCommand)
1. `RuleAst.line` (WHEN line; FOR EACH expansions inherit the macro line), set by `RuleParser`.
2. `CompileError(message, line, col)`; `AstCompiler` wraps rule compilation to tag the rule.
3. `CompileErrorLocator`: message patterns → identifier search inside the rule's text; fallback rule line; fallback section line.
4. `DiagnosticsRunner.analyze` compiles after parsing; one diagnostic shape; portfolio range no longer negative.
5. `qkt parse` prints located positions; `--json` unchanged.
6. Located errors for: `SIZING <expr> PCT RISK`, duplicate `PARAM`, `EVERY HOUR AT :75`, `BUY` on a `HUB:` alias, `SEQUENCE` stage count, recursive `LET`; undeclared alias anywhere in a rule.
7. Tests: `DiagnosticsRunnerTest` (compile errors with positions), `ParseCommandTest`, `CompileErrorLocatorTest`, one test per item in 6.

## W2 — one vocabulary (dsl/DslVocabulary, lsp/*, cli `dsl vocabulary`)
1. `DslVocabulary`: pseudo-symbol member tables (POSITION, NOW, ACCOUNT, EXIT, STREAK, TRADES, COOLDOWN, SEQUENCE), stream fields, registry-external indicators (`resid`, `confirm_ratio`), rolling shorthands (`avg`, `count`, `sum`, `calendar_window`). Parser and compiler read it.
2. `QktVocabulary` derives from it; `ARROW` excluded; `LOG/FLOOR/MIN/MAX` not duplicated.
3. `CompletionProvider`: dotted owners, basket/series aliases, `.candle`/`.tick`, member kinds.
4. `QktDocs`: hover for every keyword; `vwap` signature fixed; indicator signatures compile-checked.
5. `qkt dsl vocabulary --json` (schema `qkt-vocabulary-v1`).
6. `DocsDriftTest` extended: keywords ⊆ docs, docs ⊆ names, every indicator hover signature compiles, vocabulary JSON lists every registry name.

## W3 — generated grammars (dsl/KeywordCategory, editor/GrammarGenerator, cli `editor grammar`, editor/*)
1. `KeywordCategory` mapping for every `TokenKind` keyword.
2. `GrammarGenerator.textMate()` and `.vim()`; scopes per category plus indicators, functions, constants, pseudo-symbols, member fields, aliases, durations; single-line strings with lexer escapes; `=`/`<>` operators.
3. `qkt editor grammar --format textmate|vim`; `GrammarFilesTest` asserts `editor/textmate/qkt.tmLanguage.json`, `editor/vscode/syntaxes/qkt.tmLanguage.json`, `editor/nvim/syntax/qkt.vim` equal generator output.
4. `language-configuration.json` indent rules that can match; READMEs/CHANGELOG corrected (snippet table, LSP wording, Sublime target removed from installer and docs).

## W4 — docs are tested claims (docs/reference/dsl*, docs/how-to/editor-integrations.md, docs/reference/cli-commands.md, test/docs)
1. `DslReferenceCodeBlocksTest` covers `dsl-grammar.md`; placeholder sketches marked `grammar`; stale `skip` fixed.
2. Every false claim from the audit replaced with the true statement plus a compiled or `illegal` example.
3. Entries for the 16 undocumented indicators, 3 functions, accessors and syntax items, each with a compiled example.
4. Editor docs: diagnostics = parse + compile; `lsp`/`editor`/`parse --json`/`dsl vocabulary` rows in cli-commands.md.

## Integration
Merge W1..W4, run `./gradlew build`, regenerate grammars, run the drift tests, open the PR (closes the audit issue), promote, attest, roll bots by digest.
