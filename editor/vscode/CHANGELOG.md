# Changelog

## Unreleased

- Grammar is now generated from the language implementation (`qkt editor grammar --format textmate`): every
  lexer keyword is highlighted by category, plus indicators, functions, constants, pseudo-symbols, member
  fields and stream aliases; `=`, `<>` and `->` are operators; strings are single-line with only the lexer's
  escapes and other backslash sequences marked invalid.
- Indent rules now match `SYMBOLS`, `RULES`, `SEQUENCE`, `SCHEDULE`, `FOR EACH ... DO` and opening brackets
  (the old `LET` and `FOR EACH` line-end patterns could never match).
- Snippets: `stratfull` and `strat-ema` added.

## 0.1.0 — Initial release

- Syntax highlighting for `.qkt` files (bundles the project's TextMate grammar).
- Bracket matching and auto-pair for `{}`, `[]`, `()`, `""`, `''`.
- Line comments (`--`) and block comments (`/* ... */`).
- Starter snippets: `strategy`, `sym`, `rule`, `buy`, `buybr`, `pctrisk`, `cross`, `let`, `def`, `foreach`, `flatten`, `notnull`.
- No language client: `qkt lsp` exists but the extension does not start it.
- Not published to the VSCode marketplace — install via `.vsix`.
