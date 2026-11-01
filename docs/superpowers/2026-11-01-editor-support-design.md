# Editor support for `.qkt` everywhere

Status: implemented in three parts (#1583, #1584 and the JetBrains/Sublime/Emacs follow-up), 2026-11-01. Epic: [#71](https://github.com/elitekaycy/qkt/issues/71).

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

4. **Automatic setup.** `qkt editor install all` covers every detected editor, and `qkt create template`
   writes `.vscode/extensions.json` so VS Code and its forks offer the extension when the project opens.
   Having `install.sh` / `install.ps1` offer `qkt editor install all` at the end is a follow-up.
5. **Haskell fallback, layered so it never overrides the real grammar.** Haskell's grammar colours qkt's
   UPPERCASE keywords, `--` comments, numbers, operators and strings, and most hosts ship one.
   - GitHub: `.gitattributes` maps `*.qkt` to Haskell (already in every template).
   - Gists, Vim, Neovim and Emacs without qkt: the first-line modeline
     `-- -*- mode: haskell -*- vim: set ft=haskell:` (a qkt comment) selects Haskell.
   - With qkt installed, the Vim plugin (`FileType`/`Syntax haskell` autocommands) and `qkt-mode`
     (`haskell-mode-hook`) take a `.qkt` buffer back, so the modeline is safe to keep.
   - VS Code and JetBrains ship no Haskell grammar, so a fallback mapping would colour nothing there;
     their path is the recommended-extension prompt and the guided setup instead.

## Delivery

1. **VS Code family**: bundled language client (completion, suggestions, hover and errors with no extra
   extension), Cursor, Windsurf and VSCodium targets, Open VSX publishing in the release workflow,
   `.vscode/extensions.json` in templates.
2. **Tree-sitter, Zed and Helix**: generated tree-sitter grammar and highlight queries, a Zed extension,
   a Helix installer target.
3. **JetBrains, Sublime, Emacs and the Haskell fallback**: generated Sublime and Emacs grammars, the
   installer targets, and the modeline takeback in the Vim plugin and `qkt-mode`.

Windows `PATH` lookup for `code.cmd` / `npx.cmd` is fixed separately and not repeated here.

## Out of scope

Go-to-definition, rename and formatting in the language server; a Linguist submission (Linguist wants
the extension in wide use first). Publishing to the Marketplace and Open VSX needs the maintainer's
`VSCE_PAT` and `OVSX_PAT` secrets; without them the publish steps are skipped.
