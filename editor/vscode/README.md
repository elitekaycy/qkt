# qkt — VSCode extension

Syntax highlighting and snippets for [qkt](https://github.com/elitekaycy/qkt) strategy files (`.qkt`).

## What you get

- Syntax highlighting via the bundled TextMate grammar, a copy of [`editor/textmate/`](../textmate/) generated
  from the language implementation.
- Bracket matching and auto-pair for `{}`, `[]`, `()`, `""`, `''`.
- Comment toggling for `--` line comments and `/* ... */` block comments. The lexer also accepts `#` line
  comments; VSCode's language configuration allows one line-comment token, so toggling uses `--`.
- Indentation after `SYMBOLS`, `RULES`, `SEQUENCE ...`, `SCHEDULE ...`, `FOR EACH ... DO` and an opening
  bracket; outdent on a closing bracket.
- Starter snippets for common patterns — see [Snippets](#snippets) below.

## Install

### Via the `qkt` CLI (easiest)

```bash
qkt editor install vscode
```

Picks up the bundled `.vsix` from your qkt distribution and runs `code --install-extension`. See [docs/how-to/editor-integrations.md](../../docs/how-to/editor-integrations.md).

### From a local `.vsix`

```bash
# from repo root
cd editor/vscode
npm run package        # produces qkt-<version>.vsix
code --install-extension qkt-*.vsix
```

Reload VSCode. Open any `.qkt` file. Run **Developer: Inspect Editor Tokens and Scopes** to verify scope resolution.

### Dev workflow (live edits)

Symlink the source directory into your user-extensions folder:

```bash
ln -s "$(pwd)/editor/vscode" "$HOME/.vscode/extensions/qkt-dev"
```

Reload VSCode. Changes to `syntaxes/qkt.tmLanguage.json` or `snippets/qkt.json` take effect on next file open.

The grammar is generated, not hand-edited: run `qkt editor grammar --format textmate` into
[`editor/textmate/qkt.tmLanguage.json`](../textmate/qkt.tmLanguage.json) and `npm run sync-grammar` to copy it
into `syntaxes/`.

## Snippets

| Prefix | What it expands to |
|---|---|
| `strategy` | Minimal `STRATEGY` skeleton with `SYMBOLS` and one rule |
| `stratfull` | Full skeleton with `DEFAULTS`, `SYMBOLS`, `LET` and a bracketed rule |
| `strat-ema` | EMA crossover strategy with bracket exits |
| `sym` | A single `SYMBOLS` line with optional `WARMUP N BARS` |
| `rule` | Basic `WHEN ... THEN ...` |
| `buy` | `BUY <stream> SIZING <n>` |
| `buybr` | `BUY` with an ATR-sized `BRACKET` |
| `pctrisk` | `SIZING N PCT RISK` |
| `cross` | EMA fast/slow `CROSSES ABOVE` |
| `let` | `LET <name> = <expr>` |
| `def` | `DEFAULTS { ... }` block |
| `foreach` | `FOR EACH ... IN ... DO` over streams |
| `flatten` | Session-end `FLATTEN` rule |
| `notnull` | `<expr> IS NOT NULL` guard |

Type the prefix and press Tab; placeholders cycle with Tab.

## Language server

qkt ships a language server, `qkt lsp` (LSP over stdio), with diagnostics, completion and hover. This
extension does not include a client for it. To use it in VSCode, install a generic LSP client extension and
point it at `qkt lsp` for the `qkt` language id. See
[docs/how-to/editor-integrations.md](../../docs/how-to/editor-integrations.md).

## Limitations

- No bundled language client: without one, no completion, hover or live diagnostics in VSCode.
- Not on the VSCode marketplace; install via `.vsix`.

## Source of truth

`syntaxes/qkt.tmLanguage.json` is a committed copy of the generated
[`editor/textmate/qkt.tmLanguage.json`](../textmate/qkt.tmLanguage.json). When the language changes,
regenerate with `qkt editor grammar --format textmate`, then run `npm run sync-grammar` here.
`GrammarFilesTest` fails when either copy differs from the generator's output.

## License

Apache-2.0 — same as qkt.
