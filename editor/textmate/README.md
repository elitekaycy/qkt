# `.qkt` TextMate Grammar

Syntax highlighting for `.qkt` strategy files in TextMate-compatible editors.

## Scopes

| Scope | Highlights |
|---|---|
| `comment.line.double-dash.qkt` / `comment.line.number-sign.qkt` / `comment.block.qkt` | `-- foo`, `# foo`, `/* foo */` |
| `string.quoted.double.qkt` / `string.quoted.single.qkt` | `"foo"`, `'foo'` |
| `constant.numeric.qkt` / `constant.numeric.duration.qkt` | `1.5`, `30m` |
| `constant.language.qkt` | `TRUE`, `FALSE` |
| `keyword.control.section.qkt` | `STRATEGY`, `VERSION`, `RULES`, etc. |
| `keyword.control.flow.qkt` | `WHEN`, `THEN`, `FOR`, `EACH`, etc. |
| `keyword.other.qkt` | `BUY`, `SELL`, `LIMIT`, `STOP`, `SIZING`, `TIF`, etc. |
| `keyword.operator.qkt` | `==`, `>=`, `+`, `*`, `AND`, `OR`, `NOT` |
| `entity.name.namespace.qkt` | broker prefix in `EXNESS:XAUUSD` |

Keywords match uppercase only by design — the lexer is case-insensitive but every `.qkt` sample uses uppercase, and falling through unhighlighted is a soft style nudge.

## Installing in VSCode

Create a minimal local extension scaffold (not committed, dev-only):

```bash
mkdir -p ~/.vscode/extensions/qkt-syntax-local
cd ~/.vscode/extensions/qkt-syntax-local
ln -s /path/to/qkt/editor/textmate/qkt.tmLanguage.json qkt.tmLanguage.json
```

Create `~/.vscode/extensions/qkt-syntax-local/package.json`:

```json
{
  "name": "qkt-syntax-local",
  "publisher": "local",
  "version": "0.0.0",
  "engines": { "vscode": "^1.50.0" },
  "contributes": {
    "languages": [
      { "id": "qkt", "extensions": [".qkt"], "aliases": ["qkt"] }
    ],
    "grammars": [
      {
        "language": "qkt",
        "scopeName": "source.qkt",
        "path": "./qkt.tmLanguage.json"
      }
    ]
  }
}
```

Reload VSCode. Open any `.qkt` file. Use **Developer: Inspect Editor Tokens and Scopes** to verify scopes resolve.

## Installing in Sublime Text

Copy `qkt.tmLanguage.json` to `~/.config/sublime-text/Packages/User/qkt.sublime-syntax` (Sublime auto-converts on load).

## Installing in IntelliJ

Settings → Editor → TextMate Bundles → `+` → point to the `editor/textmate/` directory.

## Source of truth

The keyword regexes derive from `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. When a new keyword token lands there, update the corresponding bucket in `qkt.tmLanguage.json` and eyeball-verify.
