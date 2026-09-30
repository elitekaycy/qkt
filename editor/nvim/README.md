# qkt — Neovim / Vim plugin

Filetype detection, syntax highlighting, and comment-string config for [qkt](https://github.com/elitekaycy/qkt) strategy files (`.qkt`) in Neovim and Vim.

## What you get

- `.qkt` files auto-detect as `filetype=qkt`.
- Syntax highlighting generated from the language implementation: every keyword by category, indicators,
  functions, constants, pseudo-symbols, member fields, stream aliases, operators, numbers, durations,
  single-line strings with the lexer's escapes, comments and broker prefixes.
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

Open any `.qkt` file. Run `:set filetype?` — should print `filetype=qkt`. Run `:syntax sync fromstart` then `:hi qktAction` to inspect the link.

## Highlight groups

The plugin defines these custom groups and links each to a stock highlight group your colorscheme already styles. Override any link in your config to retheme:

| Custom group | Linked to | Matches |
|---|---|---|
| `qktSection` | `PreProc` | `STRATEGY`, `VERSION`, `DEFAULTS`, `SYMBOLS`, `LET`, `RULES`, `SEQUENCE`, `PORTFOLIO`, ... |
| `qktFlow` | `Conditional` | `WHEN`, `THEN`, `FOR`, `EACH`, `IN`, `DO`, `CASE`, `ELSE`, `END`, `SINCE`, `EVERY` |
| `qktAction` | `Statement` | `BUY`, `SELL`, `CLOSE`, `FLATTEN`, `RESIZE`, `CANCEL`, `LOG`, `WARN`, ... |
| `qktOrder` | `Keyword` | `MARKET`, `LIMIT`, `STOP`, `TRAILING`, `AT`, `BY`, `TIF`, `GTC`, ... |
| `qktSizing` | `Keyword` | `SIZING`, `RISK`, `USD`, `OF`, `EQUITY`, `BALANCE`, `MIN_STEP` |
| `qktBracket` | `Keyword` | `BRACKET`, `OCO`, `TAKE`, `PROFIT`, `LOSS`, `LATCH`, `ARM`, `RETRACE`, ... |
| `qktStacking` | `Keyword` | `STACK`, `STAGE`, `SPACING`, `WITHIN`, `AFTER`, `MFE`, `MAE`, ... |
| `qktPortfolio` | `Keyword` | `AS`, `RUN`, `HOLD`, `CAPITAL`, `WEIGHT`, `ALLOCATE`, `REBALANCE`, ... |
| `qktSession` | `Keyword` | `HOUR`, `WEEKDAY`, `UTC`, `NY`, `LONDON`, `WARMUP`, `BARS`, ... |
| `qktHook` | `Keyword` | `ON_FILL`, `ON_STOP`, `ON_TP`, `ON_CLOSE` |
| `qktState` | `Identifier` | `POSITION`, `NOW`, `ACCOUNT`, `EXIT`, `STREAK`, `TRADES`, `COOLDOWN`, ... |
| `qktOperatorWord` | `Operator` | `AND`, `OR`, `NOT`, `IS`, `NULL`, `BETWEEN`, `CROSSES`, `ABOVE`, `BELOW` |
| `qktAggregate` | `Function` | `OPEN`, `MAX`, `MIN`, `MEAN`, `SUM` |
| `qktBoolean` | `Boolean` | `TRUE`, `FALSE` |
| `qktIndicator` | `Function` | `ema(`, `atr(`, `zscore(`, `resid(`, ... (every registered indicator) |
| `qktFunction` | `Function` | `abs(`, `sqrt(`, `pow(`, `avg(`, `count(`, ... |
| `qktConstant` | `Constant` | `ONE_PERCENT`, `BPS`, ... |
| `qktField` | `Constant` | `.close`, `.bid`, `.tick_size`, `.qty`, `.hour_utc`, ... after a dot |
| `qktMember` | `Identifier` | any other identifier after a dot |
| `qktAlias`, `qktAliasTarget` | `Identifier` | `gold` in `gold = EXNESS:XAUUSD`, before a dot, or after `BUY`/`SELL`/`CLOSE`/`RESIZE` |
| `qktOperator` | `Operator` | `=`, `==`, `<>`, `!=`, `<=`, `>=`, `<`, `>`, `->`, `+`, `-`, `*`, `/`, `%` |
| `qktNumber` | `Number` | `100`, `1.5`, `1e-3` |
| `qktDuration` | `Number` | `5m`, `1h`, `30s`, `2d` |
| `qktBroker` | `Type` | broker prefix in `BACKTEST:BTCUSDT` |
| `qktString` | `String` | `"hello"`, `'hello'` (single-line) |
| `qktEscape`, `qktEscapeError` | `SpecialChar`, `Error` | `\\` `\'` `\"` `\n` `\t`; any other `\x` |
| `qktComment` | `Comment` | `--`, `#`, `/* */` |

Keywords match case-insensitively, as the lexer does. A word that is both a keyword and a function (`LOG`,
`FLOOR`, `MAX`, `MIN`) keeps its keyword group even when called.

Example override in `init.lua`:

```lua
vim.cmd("hi! link qktBroker Identifier")
```

## Language server

On Neovim 0.8+ the bundled `ftplugin/qkt.vim` autostarts the qkt language server (`qkt lsp`) whenever you open a `.qkt` file and `qkt` is on your `PATH`, giving you live diagnostics, completion, and hover. One server is shared across all `.qkt` buffers in a project. Opt out with `let g:qkt_no_lsp = 1` (for example, if you wire qkt through nvim-lspconfig yourself). Classic Vim, which has no built-in LSP client, simply skips this and keeps the syntax highlighting. See [docs/how-to/editor-integrations.md](../../docs/how-to/editor-integrations.md) for other editors.

## Limitations

- Go-to-definition, references, rename, and formatting are not implemented.
- No tree-sitter grammar; this is a regex syntax file.

## Source of truth

`syntax/qkt.vim` is generated by `qkt editor grammar --format vim` from
`src/main/kotlin/com/qkt/editor/VimSyntax.kt`, the same vocabulary the TextMate grammar is generated from.
Do not edit it by hand; regenerate after a language change (see [editor/README.md](../README.md)).
`GrammarFilesTest` fails on any drift. `ftdetect/` and `ftplugin/` are hand-maintained.

## License

Apache-2.0 — same as qkt.
