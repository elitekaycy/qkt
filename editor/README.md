# qkt editor tooling

Editor support files for `.qkt` strategy files.

## What's here

- **`textmate/`** — the TextMate grammar (`source.qkt`) for VSCode, IntelliJ (via TextMate Bundles) and other
  TextMate-compatible editors. See [textmate/README.md](textmate/README.md).
- **`vscode/`** — the VSCode extension: the same grammar, language configuration and snippets.
- **`nvim/`** — Neovim/Vim filetype detection, syntax file, comment settings and language-server autostart.
- **Language server** — `qkt lsp` speaks LSP over stdio and provides diagnostics, completion and hover for any
  LSP-capable editor. The Neovim plugin starts it automatically; the VSCode extension ships no client, so wire
  it through a generic LSP client extension if you want it there. See
  [docs/how-to/editor-integrations.md](../docs/how-to/editor-integrations.md).

## Generated grammars

`textmate/qkt.tmLanguage.json`, `vscode/syntaxes/qkt.tmLanguage.json` and `nvim/syntax/qkt.vim` are generated
from the language implementation (`src/main/kotlin/com/qkt/editor/`): keywords come from the lexer's token
kinds grouped by `KeywordCategory`, indicator and function names from their registries, constants from
`Constants`, stream fields and pseudo-symbol members from the compiler tables. Do not edit them by hand;
regenerate after a language change:

```bash
qkt editor grammar --format textmate > editor/textmate/qkt.tmLanguage.json
cp editor/textmate/qkt.tmLanguage.json editor/vscode/syntaxes/qkt.tmLanguage.json
qkt editor grammar --format vim > editor/nvim/syntax/qkt.vim
```

`GrammarFilesTest` fails when a checked-in file differs from the generator's output, and
`GrammarCoverageTest` fails when a keyword, indicator, function, constant or member field has no rule.

## On GitHub

GitHub cannot use these grammars. Repositories and gists borrow Linguist's Haskell grammar instead; see
[docs/how-to/github-syntax-highlighting.md](../docs/how-to/github-syntax-highlighting.md).

## Not here

There is no tree-sitter grammar, no VSCode marketplace listing and no VSCode language client. The editor-tooling
epic is [#71](https://github.com/elitekaycy/qkt/issues/71).
