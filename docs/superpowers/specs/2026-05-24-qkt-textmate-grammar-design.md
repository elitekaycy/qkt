# `.qkt` TextMate Grammar — Design

**Issue:** [#83](https://github.com/elitekaycy/qkt/issues/83) — Syntax highlighting grammar for `.qkt` files
**Epic:** [#71](https://github.com/elitekaycy/qkt/issues/71) — Editor tooling for `.qkt` files
**Date:** 2026-05-24

---

## 1. Problem

`.qkt` strategy files render as plain, uncolored text in every editor today. Keywords (`STRATEGY`, `RULES`, `WHEN`, `THEN`), strings, numbers, comments, and operators are visually indistinguishable. Reading a 200-line strategy is squint-and-parse; authoring is error-prone.

Issue #83 originally proposed shipping both a TextMate grammar and a tree-sitter grammar. During design we split the scope: this spec covers **TextMate only**. A separate follow-up issue tracks structural tree-sitter, which is large enough to warrant its own design and plan.

## 2. Goals

- A single TextMate grammar JSON file that colors `.qkt` files in every TextMate-compatible editor (VSCode, Sublime, IntelliJ via TextMate Bundles, `bat`, Atom, BBEdit).
- Coverage of every token kind the lexer produces (`src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`).
- Themable at medium granularity: themes that distinguish section keywords from flow keywords from body keywords can do so; themes that don't still get sensible defaults.
- Eyeball-verifiable against the existing `examples/hedge-straddle/hedge-straddle.qkt` sample.

## 3. Non-goals

- Tree-sitter grammar — deferred to a new follow-up issue.
- VSCode / Sublime / IntelliJ extension packaging — #85's responsibility.
- LSP features (autocomplete, hover, go-to) — #84.
- Snippets, indent rules, language configuration JSON, bracket-pair config — #85 territory.
- Marketplace publication — #85.
- Linting or parse-error squiggles — #82 + #84.

## 4. Approach

A single JSON file at `editor/textmate/qkt.tmLanguage.json` that follows the TextMate grammar specification. Lives in-repo so it stays in sync with `TokenKind.kt` changes; future #85 can copy or reference it.

### 4.1. Grammar shape

```json
{
  "name": "qkt",
  "scopeName": "source.qkt",
  "fileTypes": ["qkt"],
  "patterns": [
    { "include": "#comments" },
    { "include": "#strings" },
    { "include": "#numbers" },
    { "include": "#section-keywords" },
    { "include": "#flow-keywords" },
    { "include": "#other-keywords" },
    { "include": "#constants" },
    { "include": "#operators" },
    { "include": "#stream-prefix" }
  ],
  "repository": {
    "comments": { ... },
    "strings": { ... },
    "numbers": { ... },
    "section-keywords": { ... },
    "flow-keywords": { ... },
    "other-keywords": { ... },
    "constants": { ... },
    "operators": { ... },
    "stream-prefix": { ... }
  }
}
```

The `patterns` list defines top-level dispatch order; `repository` holds the named sub-patterns referenced by `#include`. Order matters — comments and strings are dispatched first so they swallow their content before keyword matchers see it.

### 4.2. Scope inventory (12 scopes + 1 structural)

| Scope | Captures |
|---|---|
| `comment.line.double-dash.qkt` | `-- ...` to end-of-line |
| `comment.line.number-sign.qkt` | `# ...` to end-of-line |
| `comment.block.qkt` | `/* ... */`, multi-line |
| `string.quoted.double.qkt` | `"..."` with `\n \t \\ \" \'` escapes |
| `string.quoted.single.qkt` | `'...'` with `\n \t \\ \" \'` escapes |
| `constant.numeric.qkt` | integers, decimals, scientific notation |
| `constant.numeric.duration.qkt` | integer + `s`/`m`/`h`/`d` suffix |
| `constant.language.qkt` | `TRUE`, `FALSE` |
| `keyword.control.section.qkt` | `STRATEGY VERSION DEFAULTS SYMBOLS LET RULES PORTFOLIO IMPORT` |
| `keyword.control.flow.qkt` | `WHEN THEN FOR EACH IN DO AS RUN HOLD CASE ELSE END SINCE` |
| `keyword.other.qkt` | the remaining ~60 keywords (see §4.3) |
| `keyword.operator.qkt` | `+ - * / % == != > < >= <= AND OR NOT` |
| `entity.name.namespace.qkt` | broker prefix and `:` in `EXNESS:XAUUSD` |

Rationale: three keyword classes is enough for themes that differentiate section headers from flow keywords from action/option keywords. Most themes already define distinct colors for `keyword.control.*` vs `keyword.other.*`. Operators stay one bucket — diminishing returns to split further.

### 4.3. Keyword bucket mapping

Derived directly from `TokenKind.kt`:

**`keyword.control.section.qkt`** (8 keywords):
`STRATEGY`, `VERSION`, `DEFAULTS`, `SYMBOLS`, `LET`, `RULES`, `PORTFOLIO`, `IMPORT`

**`keyword.control.flow.qkt`** (13 keywords):
`WHEN`, `THEN`, `FOR`, `EACH`, `IN`, `DO`, `AS`, `RUN`, `HOLD`, `CASE`, `ELSE`, `END`, `SINCE`

**`keyword.other.qkt`** — every other keyword. Groups for readability (all map to the same scope):

- *Actions*: `BUY`, `SELL`, `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL`, `LOG`, `WARN`, `ERROR`, `DEBUG`
- *Order types*: `MARKET`, `LIMIT`, `STOP`, `TRAILING`, `BRACKET`, `OCO`, `OCO_ENTRY`, `STACK`, `STACK_AT`
- *Order options*: `AT`, `BY`, `PCT`, `ORDER_TYPE`, `STOP_LOSS`, `TAKE_PROFIT`, `TAKE`, `PROFIT`, `LOSS`, `RR`
- *Sizing / risk*: `SIZING`, `RISK`, `USD`, `OF`, `EQUITY`, `BALANCE`, `POSITION`, `POSITION_AVG_PRICE`, `OPEN_ORDERS`, `ENTRY_QTY`
- *Time in force*: `TIF`, `GTC`, `IOC`, `FOK`, `DAY`, `GTD`
- *Comparison / aggregate*: `EVERY`, `CROSSES`, `ABOVE`, `BELOW`, `BETWEEN`, `OPEN`, `MAX`, `MIN`, `MEAN`, `SUM`
- *Stack / timing*: `SPACING`, `WITHIN`, `MFE`
- *Built-ins*: `ACCOUNT`, `SYMBOL`, `NOW`

`AND`, `OR`, `NOT` are tokens in `TokenKind` but bucketed under `keyword.operator.qkt` because they're logical operators in practice — themes color them like `+ - * /`, not like `BUY SELL`.

Total keyword tokens covered: 8 (section) + 13 (flow) + 61 (other) + 3 logical operators (`AND`, `OR`, `NOT` bucketed as `keyword.operator.qkt`) + 2 booleans (`TRUE`, `FALSE` bucketed as `constant.language.qkt`) = **87 keywords**, matching the keyword set in `TokenKind.kt`. Non-keyword tokens (`NUMBER`, `STRING`, `IDENT`, `DURATION`, `EOF`, operator/punctuation symbols) are matched by their own pattern classes.

### 4.4. Pattern detail

**Comments** (matched first, swallow content):
- `--.*$` → `comment.line.double-dash.qkt`
- `#.*$` → `comment.line.number-sign.qkt`
- `/\* ... \*/` → `comment.block.qkt` (begin/end pattern, multi-line)

The lexer (`Lexer.skipWhitespaceAndComments`) accepts `--foo` and `#foo` with no required space after the marker; the grammar matches the same.

**Strings** (matched second):
- `"..."` with escape support → `string.quoted.double.qkt`, escapes scoped `constant.character.escape.qkt`
- `'...'` analogous → `string.quoted.single.qkt`

**Numbers and durations** (order matters: duration first):
- `\b\d+[smhd]\b` → `constant.numeric.duration.qkt`
- `\b\d+(\.\d+)?([eE][+-]?\d+)?\b` → `constant.numeric.qkt`

**Keywords** (word-boundary anchored, **uppercase-only**):

The lexer is case-insensitive (it uppercases identifiers before keyword lookup in `Lexer.readIdentOrKeyword`), so `buy gold market` parses as valid syntax. The grammar deliberately matches only the uppercase form. This is a soft style nudge — a lowercase `buy` falls through to the default identifier scope and reads as unhighlighted, telling the author "you're outside the canonical style." Every committed `.qkt` sample uses uppercase keywords.

Patterns:
- `\b(STRATEGY|VERSION|DEFAULTS|SYMBOLS|LET|RULES|PORTFOLIO|IMPORT)\b` → section
- `\b(WHEN|THEN|FOR|EACH|IN|DO|AS|RUN|HOLD|CASE|ELSE|END|SINCE)\b` → flow
- `\b(BUY|SELL|...|NOW)\b` → other
- `\b(TRUE|FALSE)\b` → constant.language

**Operators**:
- `==|!=|<=|>=|<|>|\+|-|\*|/|%` → `keyword.operator.qkt`
- `\b(AND|OR|NOT)\b` → `keyword.operator.qkt`

**Stream prefix** (`EXNESS:XAUUSD`, `BACKTEST:BTCUSDT`):
- `\b([A-Z][A-Z0-9_]*):` → match captures the broker name and the colon; tagged `entity.name.namespace.qkt`. The symbol after `:` falls through to default identifier scope.

### 4.5. Source-of-truth invariant

The grammar's keyword regexes are derived from `TokenKind.kt`. When a new token is added there, the grammar must be updated correspondingly. The spec documents this as a manual sync protocol (verified by eyeballing the sample file after a token addition). Automated drift detection is out of scope for #83 but is a reasonable future ticket.

## 5. Verification

Manual eyeball test:

1. Install the grammar into VSCode by symlinking `editor/textmate/qkt.tmLanguage.json` into a tiny local extension scaffold (the spec includes the 6-line `package.json` for this — copy-paste, not packaged).
2. Open `examples/hedge-straddle/hedge-straddle.qkt`.
3. Use VSCode's "Developer: Inspect Editor Tokens and Scopes" command to walk through:
   - `STRATEGY hedge_straddle VERSION 1` → `STRATEGY` and `VERSION` scoped `keyword.control.section.qkt`; identifier `hedge_straddle` falls through; `1` scoped `constant.numeric.qkt`.
   - `# Pending-mode hedge straddle.` → entire line scoped `comment.line.number-sign.qkt`.
   - `EXNESS:XAUUSD` → `EXNESS:` scoped `entity.name.namespace.qkt`; `XAUUSD` falls through.
   - `EVERY 5m` → `EVERY` scoped `keyword.other.qkt`; `5m` scoped `constant.numeric.duration.qkt`.
   - `WHEN NOW.hour_utc IN [6, 7, ...]` → `WHEN` flow, `NOW` other, `IN` flow.
   - `STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06` → `STACK_AT MFE WITHIN SIZING` other, `>=` operator, `10` numeric, `30m` duration, `0.06` numeric.
4. Commit a screenshot to `editor/textmate/screenshot.png` showing the colored sample.

No automated tests in CI — TextMate has no native test framework, and adding `vscode-tmgrammar-test` would introduce a Node toolchain to the Gradle-only repo for a single grammar file. Cost > benefit.

## 6. File layout

```
editor/
└── textmate/
    ├── qkt.tmLanguage.json     # the grammar
    ├── README.md                # how to install in VSCode / Sublime / IntelliJ
    └── screenshot.png           # verification artifact
```

`editor/README.md` (top-level) gets a short pointer explaining what's in this dir and what's coming (tree-sitter follow-up, #85 packaging).

## 7. Open questions

None. All design forks resolved:
- Scope: TextMate only (tree-sitter deferred to new issue)
- Layout: in-repo `editor/textmate/`
- Granularity: medium (12 scopes + 1 structural)
- Verification: manual eyeball, no CI tests

## 8. Risk

Low. Pure data file. Worst case: a token is mis-scoped, leading to wrong color in some themes — fixable in a follow-up commit, no production behavior impact, no tests to break, no migrations.

## 9. Future / dependencies

- Tree-sitter structural grammar — new follow-up issue, multi-session.
- #82 — `.qkt` parser/AST/linter library — could share the keyword catalogue once both exist; not blocking.
- #84 — LSP — depends on #82, layers diagnostics on top of highlighting.
- #85 — VSCode extension — packages this grammar + #84's LSP into a marketplace-ready extension.
- #86 — Neovim plugin — uses the tree-sitter grammar (when shipped) + #84's LSP.

## 10. References

- Issue: [#83](https://github.com/elitekaycy/qkt/issues/83)
- Epic: [#71](https://github.com/elitekaycy/qkt/issues/71)
- TextMate grammar spec: https://macromates.com/manual/en/language_grammars
- VSCode syntax highlighting guide: https://code.visualstudio.com/api/language-extensions/syntax-highlight-guide
- Source of truth for tokens: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Source of truth for lexer rules: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
