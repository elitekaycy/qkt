# Editor support for `.qkt` everywhere

Status: proposed, 2026-11-01. Epic: [#71](https://github.com/elitekaycy/qkt/issues/71).

## Goal

Opening a `.qkt` file should feel like opening any mainstream language: colours, completion,
suggestions, hover and live errors, in every common editor, with one command (or none). When the
native integration is not installed, the file should still be coloured, by borrowing Haskell.

## What already exists

- `qkt lsp`: a language server inside the `qkt` binary (diagnostics, completion, hover), built on the
  same parser and compiler that run strategies.
- Generated grammars: `qkt editor grammar --format textmate|vim`, drift-checked by `GrammarFilesTest`.
- `qkt editor install vscode|nvim|vim`. Neovim autostarts the language server; VS Code gets colours and
  snippets but no language client.
- GitHub borrows Haskell through `.gitattributes`.

## Design

Two engines, many thin wrappers. Every editor package is a shell around two things qkt already owns or
can generate, so a language change reaches every editor by regenerating, never by hand edits.

1. **One language server, shipped in the `qkt` binary.** All smart features (completion, suggestions,
   hover, diagnostics, document symbols) live in `qkt lsp`. Editors only launch `qkt lsp` over stdio.
   No editor gets its own completion logic, so nothing drifts.
2. **Generated highlighting grammars from one vocabulary.** `GrammarVocabulary` already feeds TextMate
   and Vim. Add three more renderers off the same model:
   - **tree-sitter** (`grammar.js` + `queries/highlights.scm`) for Zed, Helix and Neovim tree-sitter;
   - **Sublime** `.sublime-syntax`;
   - **Emacs** `qkt-mode.el` font-lock.
   All are added to `GrammarFilesTest`, so CI fails when a checked-in file is stale.
3. **Per-editor packages are wrappers** that bind `*.qkt` to the grammar and point the LSP client at
   `qkt lsp`:

| Editor | Highlighting | Smart features | How it gets installed |
|---|---|---|---|
| VS Code, Cursor, Windsurf, VSCodium | TextMate | bundled LSP client in the extension | Marketplace + Open VSX, or `qkt editor install <name>` |
| Neovim | Vim syntax (tree-sitter optional) | built-in `vim.lsp`, autostarted | `qkt editor install nvim`, or lazy.nvim |
| Vim | Vim syntax | vim-lsp / coc config snippet | `qkt editor install vim` |
| Zed | tree-sitter | extension declares `qkt lsp` | Zed extension (`qkt editor install zed` for dev install) |
| Helix | tree-sitter | `languages.toml` entry | `qkt editor install helix` writes the entry |
| JetBrains (IntelliJ, PyCharm, ...) | TextMate bundle | LSP4IJ user server | `qkt editor install intellij` places the bundle |
| Sublime Text | `.sublime-syntax` | Sublime LSP package config | `qkt editor install sublime` |
| Emacs | `qkt-mode.el` | eglot registration in the mode | `qkt editor install emacs` |

4. **Automatic setup.** `qkt editor install all` (already present) covers every detected editor. The
   `install.sh` / `install.ps1` installers offer it once at the end, and `qkt create template` writes
   `.vscode/extensions.json` so VS Code and Cursor prompt to install the extension when the project opens.
5. **Haskell fallback, layered so it never overrides the real grammar:**
   - GitHub and gists: `.gitattributes` and the `-- -*- mode: haskell -*-` modeline (done).
   - Vim/Neovim: the ftplugin loads Haskell syntax when the qkt syntax file is missing.
   - VS Code family: the extension is the language owner; without it, `qkt editor install` for an
     editor it cannot fully support writes a `files.associations` `*.qkt -> haskell` entry instead.
   - Editors with no qkt package: `qkt editor install <editor> --fallback` maps `*.qkt` to Haskell in
     that editor's config, so any editor with a Haskell grammar colours qkt with no qkt package at all.

## Delivery

1. **VS Code family**: bundled language client (completion, suggestions, hover and errors with no extra
   extension), Cursor, Windsurf and VSCodium targets, Open VSX publishing in the release workflow,
   `.vscode/extensions.json` in templates.
2. **Tree-sitter, Zed and Helix**: generated tree-sitter grammar and highlight queries, a Zed extension,
   a Helix installer target.
3. **JetBrains, Sublime, Emacs and the Haskell fallback**: generated Sublime and Emacs grammars, the
   installer targets, and the `--fallback` mapping.

Windows `PATH` lookup for `code.cmd` / `npx.cmd` is fixed separately and not repeated here.

## Out of scope

Go-to-definition, rename and formatting in the language server; a Linguist submission (Linguist wants
the extension in wide use first). Publishing to the Marketplace and Open VSX needs the maintainer's
`VSCE_PAT` and `OVSX_PAT` secrets; without them the publish steps are skipped.
