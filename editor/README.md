# qkt editor tooling

Editor support files for `.qkt` strategy files.

## What's here

- **`textmate/`** — the TextMate grammar (`source.qkt`) for VSCode, IntelliJ (via TextMate Bundles) and other
  TextMate-compatible editors. See [textmate/README.md](textmate/README.md).
- **`vscode/`** — the extension for VS Code, Cursor, Windsurf and VSCodium: the same grammar, language
  configuration and snippets, plus a bundled language client that starts `qkt lsp`.
- **`tree-sitter-qkt/`** — the generated tree-sitter grammar and Helix highlight queries.
- **`zed/`** — the Zed extension: tree-sitter highlighting and `qkt lsp`.
- **`nvim/`** — Neovim/Vim filetype detection, syntax file, comment settings and language-server autostart.
- **Language server** — `qkt lsp` speaks LSP over stdio and provides diagnostics, completion and hover for any
  LSP-capable editor. The Neovim plugin and the VS Code extension start it automatically. See
  [docs/how-to/editor-integrations.md](../docs/how-to/editor-integrations.md).

## Generated grammars

`textmate/qkt.tmLanguage.json`, `vscode/syntaxes/qkt.tmLanguage.json`, `nvim/syntax/qkt.vim`,
`tree-sitter-qkt/grammar.js`, `tree-sitter-qkt/queries/highlights.scm` and `zed/languages/qkt/highlights.scm` are generated
from the language implementation (`src/main/kotlin/com/qkt/editor/`): keywords come from the lexer's token
kinds grouped by `KeywordCategory`, indicator and function names from their registries, constants from
`Constants`, stream fields and pseudo-symbol members from the compiler tables. Do not edit them by hand;
regenerate after a language change:

```bash
qkt editor grammar --format textmate > editor/textmate/qkt.tmLanguage.json
cp editor/textmate/qkt.tmLanguage.json editor/vscode/syntaxes/qkt.tmLanguage.json
qkt editor grammar --format vim > editor/nvim/syntax/qkt.vim
qkt editor grammar --format tree-sitter > editor/tree-sitter-qkt/grammar.js
qkt editor grammar --format helix-highlights > editor/tree-sitter-qkt/queries/highlights.scm
qkt editor grammar --format zed-highlights > editor/zed/languages/qkt/highlights.scm
(cd editor/tree-sitter-qkt && npx tree-sitter-cli@0.24.7 generate)
```

`GrammarFilesTest` fails when a checked-in file differs from the generator's output, and
`GrammarCoverageTest` fails when a keyword, indicator, function, constant or member field has no rule.

## On GitHub

GitHub cannot use these grammars. Repositories and gists borrow Linguist's Haskell grammar instead; see
[docs/how-to/github-syntax-highlighting.md](../docs/how-to/github-syntax-highlighting.md).

## Not here

The plan for the remaining editors is in
[the editor support design](../docs/superpowers/2026-11-01-editor-support-design.md); the editor-tooling epic
is [#71](https://github.com/elitekaycy/qkt/issues/71).
