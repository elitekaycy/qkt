# qkt — Neovim / Vim plugin

Filetype detection, syntax highlighting, and comment-string config for [qkt](https://github.com/elitekaycy/qkt) strategy files (`.qkt`) in Neovim and Vim.

## What you get

- `.qkt` files auto-detect as `filetype=qkt`.
- Syntax highlighting that mirrors the project's TextMate grammar (sections, flow, keywords, operators, numbers, durations, strings, comments, broker prefixes).
- Comment-string set so `gcc` (vim-commentary, mini.comment, Comment.nvim) inserts `--` line comments.
- No external dependencies. No tree-sitter required. Works in any Vim 7+ / Neovim.

## Install

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

## Limitations

- No completion, hover, go-to-definition, or live diagnostics. Those land with the language server ([#84](https://github.com/elitekaycy/qkt/issues/84)).
- No tree-sitter grammar yet — tracked under [#117](https://github.com/elitekaycy/qkt/issues/117). Once it ships, the tree-sitter highlight queries will supersede this hand-written syntax file.
- Keyword highlighting is uppercase-only by design (matches the convention every `.qkt` sample uses), even though the parser is case-insensitive.

## Source of truth

The keyword sets here mirror [`editor/textmate/qkt.tmLanguage.json`](../textmate/qkt.tmLanguage.json), which in turn mirrors `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. When a new keyword token lands in the lexer, update the textmate grammar first, then mirror into `syntax/qkt.vim` here.

## License

Apache-2.0 — same as qkt.
