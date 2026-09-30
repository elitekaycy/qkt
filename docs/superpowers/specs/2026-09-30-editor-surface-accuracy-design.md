# Editor surface accuracy — design

Date: 2026-09-30. Status: accepted.

## Problem

qkt-backtester embeds a `.qkt` editor and relies on qkt for diagnostics, completion, hover and
syntax highlighting. An audit on dev 41812ade found the hand-written parts of that surface
drifting from the language:

- The language server ran the parser only, so compile-stage errors (unknown indicator, alias,
  function, arity, bracket and sizing rules) never reached the editor, while the docs claimed
  its diagnostics "match `qkt parse` exactly". `qkt parse` reported every compile error at 1:1.
- Completion knew stream fields only; members of `POSITION.`, `NOW.`, `ACCOUNT.`, `EXIT.`,
  `STREAK.`, `TRADES.`, `COOLDOWN.`, `SEQUENCE.`, basket and series aliases, `.candle`/`.tick`,
  the special-cased indicators `resid`/`confirm_ratio` and the rolling shorthands `avg`/`count`
  were missing; candle fields were offered after `POSITION.x.`; `ARROW` was offered as a keyword.
- Hover documented a `vwap` form that does not compile and covered 39 of 154 keywords.
- The TextMate and Vim grammars were hand-maintained: 61 keywords unhighlighted, no indicator,
  function or pseudo-symbol names, `=` never highlighted, illegal escapes and multi-line strings
  drawn as legal.
- `docs/reference/dsl-grammar.md` was outside the code-block drift test and held three wrong
  examples; 26 prose claims across the DSL reference were false or stale; 16 indicators, 3
  functions and about 22 accessors were undocumented.
- The backtester kept seven copies of qkt knowledge (grammar, fields, actions, `POSITION`
  members, MA names, duration regex); four had drifted.

## Design

One principle: every editor-facing fact is derived from the implementation or tested against it.

1. **Diagnostics run the compiler.** `DiagnosticsRunner` parses, then compiles (`AstCompiler` /
   `PortfolioLoader` semantics), exactly like `qkt parse`. Compile failures become
   `CompileError(message, line, col)`; parse and compile diagnostics share one shape.
2. **Compile errors carry positions.** `RuleAst` records the source line of its `WHEN` (and
   `FOR EACH` expansions keep the macro's line). The compiler tags the rule it is compiling; a
   `CompileErrorLocator` searches that rule's text for the offending identifier named in the
   message (indicator, alias, function, reference, field) and falls back to the rule's `WHEN`
   line, then to the section keyword. `qkt parse` prints the same positions. Inputs that used to
   escape as bare exceptions (`SIZING expr PCT RISK`, duplicate `PARAM`, bad `EVERY HOUR AT`,
   `BUY` on a `HUB:` alias, `SEQUENCE` stage counts, recursive `LET`) become located errors.
   An undeclared stream alias anywhere in a rule is a compile error, not a first-evaluation crash.
3. **One vocabulary.** `DslVocabulary` (dsl package) is the single source for pseudo-symbol
   member tables, stream field names, the indicators compiled outside the registry, and the
   rolling shorthands; the parser, compiler and LSP read it. Completion resolves dotted owners
   (`POSITION.btc.`, `SEQUENCE.setup.`), baskets and series aliases, and `.candle`/`.tick`
   where an indicator argument is expected. Every keyword has hover text; `DocsDriftTest`
   enforces keywords ⊆ docs, docs ⊆ known names, and the hover signature of every registered
   indicator compiles.
4. **Generated grammars.** `GrammarGenerator` emits the TextMate grammar and the Vim syntax from
   `TokenKind` (grouped by `KeywordCategory`), the indicator and function registries, the
   constants and `DslVocabulary`. Scopes distinguish sections, actions, clauses, portfolio
   keywords, indicators, functions, constants, pseudo-symbols and member fields; strings are
   single-line with only the lexer's escapes; `=` and `<>` are operators. `qkt editor grammar
   --format textmate|vim` prints them; a test asserts the checked-in files equal the generator's
   output, so the grammars cannot drift.
5. **Machine-readable reference.** `qkt dsl vocabulary --json` prints keywords by category,
   indicators with arity, functions, constants, stream fields and pseudo-symbol members. The
   backtester reads this and the grammar from the qkt it runs instead of keeping lists.
6. **Docs are tested claims.** `dsl-grammar.md` joins the code-block drift test; placeholder
   sketches are marked `grammar`. Each false prose claim is replaced by the true statement plus a
   compiled example (or an `illegal` block for what is rejected). Undocumented indicators,
   functions, accessors and syntax get entries with compiled examples. Editor READMEs describe
   only what the code does.

## Out of scope

Go-to-definition, rename, formatting, semantic tokens, a VS Code language client, tree-sitter,
and a Sublime `.sublime-syntax` generator (the installer's Sublime target is removed rather than
left writing a file Sublime cannot load).
