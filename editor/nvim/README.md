# qkt — Neovim / Vim plugin

Filetype detection, syntax highlighting, and comment-string config for [qkt](https://github.com/elitekaycy/qkt) strategy files (`.qkt`) in Neovim and Vim.

## What you get

- `.qkt` files auto-detect as `filetype=qkt`.
- Syntax highlighting that mirrors the project's TextMate grammar (sections, flow, keywords, operators, numbers, durations, strings, comments, broker prefixes).
- Comment-string set so `gcc` (vim-commentary, mini.comment, Comment.nvim) inserts `--` line comments.
- Live diagnostics, completion, and hover on Neovim 0.8+ via the bundled language server (autostarted from `qkt lsp` — see below).
- No external dependencies. No tree-sitter required. Works in any Vim 7+ / Neovim.

## Install

### Via the `qkt` CLI (easiest, no plugin manager)

```bash
qkt editor install nvim   # or vim
```

Copies the three files into `$XDG_CONFIG_HOME/nvim/{ftdetect,ftplugin,syntax}/`. If you already use a plugin manager (lazy/packer/vim-plug), the command detects it and warns; pass `--yes` to override or use the snippets below instead. See [docs/how-to/editor-integrations.md](../../docs/how-to/editor-integrations.md).

### [lazy.nvim](https://github.com/folke/lazy.nvim)

```lua
{
  "elitekaycy/qkt",
  ft = "qkt",
  -- the plugin lives in editor/nvim/ of the repo
  config = function(plugin)
    vim.opt.rtp:append(plugin.dir .. "/editor/nvim")
  end,
}
```

If the monorepo layout doesn't play well with your plugin manager, copy the three files into your runtimepath manually:

```bash
mkdir -p ~/.config/nvim/{ftdetect,ftplugin,syntax}
cp editor/nvim/ftdetect/qkt.vim ~/.config/nvim/ftdetect/
cp editor/nvim/ftplugin/qkt.vim ~/.config/nvim/ftplugin/
cp editor/nvim/syntax/qkt.vim   ~/.config/nvim/syntax/
```

For classic Vim, replace `~/.config/nvim/` with `~/.vim/`.

### [packer.nvim](https://github.com/wbthomason/packer.nvim)

```lua
use {
  "elitekaycy/qkt",
  ft = "qkt",
  rtp = "editor/nvim",
}
```

### [vim-plug](https://github.com/junegunn/vim-plug)

```vim
Plug 'elitekaycy/qkt', { 'rtp': 'editor/nvim', 'for': 'qkt' }
```

## Verify

Open any `.qkt` file. Run `:set filetype?` — should print `filetype=qkt`. Run `:syntax sync fromstart` then `:hi qktKeyword` to inspect the link.

## Highlight groups

The plugin defines these custom groups and links each to a stock highlight group your colorscheme already styles. Override any link in your config to retheme:

| Custom group | Linked to | Matches |
|---|---|---|
| `qktSection` | `PreProc` | `STRATEGY`, `VERSION`, `DEFAULTS`, `SYMBOLS`, `LET`, `RULES`, `PORTFOLIO`, `IMPORT` |
| `qktFlow` | `Conditional` | `WHEN`, `THEN`, `FOR`, `EACH`, `IN`, `DO`, `AS`, `RUN`, `HOLD`, `CASE`, `ELSE`, `END`, `SINCE` |
| `qktKeyword` | `Keyword` | actions, sizing, brackets, OCO, TIF, indicators, etc. |
| `qktOperator` | `Operator` | `==`, `!=`, `<=`, `>=`, `<`, `>`, `+`, `-`, `*`, `/`, `%`, `AND`, `OR`, `NOT`, `IS`, `NULL` |
| `qktBoolean` | `Boolean` | `TRUE`, `FALSE` |
| `qktNumber` | `Number` | `100`, `1.5`, `1e-3` |
| `qktDuration` | `Number` | `5m`, `1h`, `30s` |
| `qktBroker` | `Type` | broker prefix in `BACKTEST:BTCUSDT` |
| `qktString` | `String` | `"hello"`, `'hello'` |
| `qktComment` | `Comment` | `--`, `#`, `/* */` |

Example override in `init.lua`:

```lua
vim.cmd("hi! link qktBroker Identifier")
```

## Language server

On Neovim 0.8+ the bundled `ftplugin/qkt.vim` autostarts the qkt language server (`qkt lsp`) whenever you open a `.qkt` file and `qkt` is on your `PATH`, giving you live diagnostics, completion, and hover. One server is shared across all `.qkt` buffers in a project. Opt out with `let g:qkt_no_lsp = 1` (for example, if you wire qkt through nvim-lspconfig yourself). Classic Vim, which has no built-in LSP client, simply skips this and keeps the syntax highlighting. See [docs/how-to/editor-integrations.md](../../docs/how-to/editor-integrations.md) for other editors.

## Limitations

- Go-to-definition, references, rename, and formatting are not implemented yet — they need source positions on the parser's AST.
- No tree-sitter grammar yet — tracked under [#117](https://github.com/elitekaycy/qkt/issues/117). Once it ships, the tree-sitter highlight queries will supersede this hand-written syntax file.
- Keyword highlighting is uppercase-only by design (matches the convention every `.qkt` sample uses), even though the parser is case-insensitive.

## Source of truth

The keyword sets here mirror [`editor/textmate/qkt.tmLanguage.json`](../textmate/qkt.tmLanguage.json), which in turn mirrors `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. When a new keyword token lands in the lexer, update the textmate grammar first, then mirror into `syntax/qkt.vim` here.

## License

Apache-2.0 — same as qkt.
