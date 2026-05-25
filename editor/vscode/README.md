# qkt — VSCode extension

Syntax highlighting and snippets for [qkt](https://github.com/elitekaycy/qkt) strategy files (`.qkt`).

## What you get

- Syntax highlighting via the bundled TextMate grammar (shared with the TextMate / Sublime / IntelliJ editor packages under [`editor/textmate/`](../textmate/)).
- Bracket matching and auto-pair for `{}`, `[]`, `()`, `""`, `''`.
- Line comments (`--`) and block comments (`/* ... */`).
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

### Dev workflow (live grammar edits)

Symlink the source directory into your user-extensions folder:

```bash
ln -s "$(pwd)/editor/vscode" "$HOME/.vscode/extensions/qkt-dev"
```

Reload VSCode. Changes to `syntaxes/qkt.tmLanguage.json` or `snippets/qkt.json` take effect on next file open.

If you want to edit the grammar itself, edit [`editor/textmate/qkt.tmLanguage.json`](../textmate/qkt.tmLanguage.json) (the source of truth) and run `npm run sync-grammar` to copy it into `syntaxes/`.

## Snippets

| Prefix | What it expands to |
|---|---|
| `strategy` | Full STRATEGY skeleton with DEFAULTS, SYMBOLS, and one rule |
| `sym` | A single `SYMBOLS` line with optional `WARMUP N BARS` |
| `rule` | Basic `WHEN ... THEN ...` |
| `buy` | `BUY <stream> SIZING <n>` |
| `buybr` | `BUY` with an ATR-sized `BRACKET` |
| `pctrisk` | `SIZING N PCT RISK` (phase 24) |
| `cross` | EMA fast/slow `CROSSES ABOVE` |
| `let` | `LET <name> = <expr>` |
| `def` | `DEFAULTS { ... }` block |
| `foreach` | `FOR EACH ... IN ... DO` over streams |
| `flatten` | Session-end `FLATTEN` rule (phase 24) |
| `notnull` | `<expr> IS NOT NULL` guard (phase 24) |

Type the prefix and press Tab; placeholders cycle with Tab.

## Limitations

- No language server yet — no completion, hover, go-to-definition, or live diagnostics. Those land with [`#84`](https://github.com/elitekaycy/qkt/issues/84).
- Not yet on the VSCode marketplace; install via `.vsix` for now.

## Source of truth

The grammar at `syntaxes/qkt.tmLanguage.json` is a committed copy of [`editor/textmate/qkt.tmLanguage.json`](../textmate/qkt.tmLanguage.json). When keywords change in `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`, update the textmate grammar there first, then run `npm run sync-grammar` here.

## License

Apache-2.0 — same as qkt.
