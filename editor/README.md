# qkt editor tooling

Editor support files for `.qkt` strategy files.

## What's here

- **`textmate/`** — TextMate grammar for syntax highlighting. Works in VSCode, Sublime Text, IntelliJ (via TextMate Bundles), `bat`, Atom, and any other TextMate-compatible editor. See [textmate/README.md](textmate/README.md) for installation.
- **`nvim/`**, **`vscode/`** — per-editor highlighting, snippets, and (Neovim) language-server autostart.
- **Language server** — `qkt lsp` provides diagnostics, completion, and hover for any LSP-capable editor. See [docs/how-to/editor-integrations.md](../docs/how-to/editor-integrations.md) for per-editor setup.

## What's not here yet

- Tree-sitter grammar (structural parser for Neovim, Zed, Helix, modern GitHub highlighting) — follow-up issue.
- VSCode marketplace extension, including the language client that wires `qkt lsp` into VSCode — tracked in [#85](https://github.com/elitekaycy/qkt/issues/85).

All of the above are part of the editor-tooling epic [#71](https://github.com/elitekaycy/qkt/issues/71).
