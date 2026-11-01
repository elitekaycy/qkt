# Editor integrations — install via `qkt editor`

`qkt editor` installs the bundled editor integrations onto your machine without you having to clone the repo, copy files by hand, or build the VSCode extension yourself. The integrations themselves live under `editor/` in the source tree; the qkt distribution tarball ships them under `share/editor/`.

Everything the editors know about the language is derived from the implementation: the syntax
grammars are generated from the parser's token table and the indicator, function and constant
registries (`qkt editor grammar`), and diagnostics, completion and hover come from the same
parser and compiler that `qkt parse` runs (`qkt lsp`).

## See what's supported

```bash
qkt editor list
```

Prints every supported editor and whether it's detected on this machine:

```
qkt editor — bundled at: /home/you/.local/share/qkt/share/editor

Supported targets:
  vscode     VSCode         [detected]
  cursor     Cursor         [detected]
  windsurf   Windsurf       [not found]
  codium     VSCodium       [not found]
  nvim       Neovim         [detected]
  vim        Vim            [not found]
  helix      Helix          [detected]
  zed        Zed            [not found]
  sublime    Sublime Text   [not found]
  emacs      Emacs          [detected]
  intellij   JetBrains IDEs [not found]
```

## Install for one editor

```bash
qkt editor install vscode
qkt editor install cursor     # also: windsurf, codium
qkt editor install nvim
qkt editor install vim
qkt editor install helix
qkt editor install zed        # prints the steps; Zed installs extensions itself
qkt editor install sublime
qkt editor install emacs
qkt editor install intellij   # prints the steps; also idea, pycharm, webstorm, ...
```

What each does:

- **vscode**, **cursor**, **windsurf**, **codium** — run that editor's own command (`code`, `cursor`, `windsurf`, `codium`) with `--install-extension <bundled.vsix>`. Every VS Code fork takes the same `.vsix`. If no `.vsix` is bundled (uncommon — release assets include one), falls back to `npm ci` and `npx @vscode/vsce package` against the bundled source. If neither route works, prints the GitHub release URL.
- **nvim** — copies `qkt.vim` into `$XDG_CONFIG_HOME/nvim/{ftdetect,ftplugin,syntax}/`.
- **vim** — same, into `~/.vim/{ftdetect,ftplugin,syntax}/`.
- **helix** — adds a managed block to `~/.config/helix/languages.toml` (`%AppData%\helix` on Windows) that binds `*.qkt`, starts `qkt lsp` and points the grammar at the bundled tree-sitter source; copies the highlight queries to `runtime/queries/qkt/`; and compiles the grammar into `runtime/grammars/` when `cc`, `gcc` or `clang` is on `PATH` (otherwise run `hx --grammar build`). The rest of `languages.toml` is left alone, and re-running replaces the block.
- **sublime** — copies the grammar (`qkt.tmLanguage`) and comment settings into `Packages/User/qkt/`, and prints the [LSP package](https://packagecontrol.io/packages/LSP) client entry for completion and diagnostics.
- **emacs** — copies `qkt-mode.el` into `~/.emacs.d/qkt/` (or `~/.config/emacs/qkt/`) and adds a managed block to your init file (`~/.emacs` if you use one, else `init.el`) that loads it. `qkt-mode` starts eglot against `qkt lsp` itself.
- **intellij** — prints the two settings steps for JetBrains IDEs: the TextMate bundle (the bundled VS Code extension folder) and an [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) language server running `qkt lsp`. JetBrains IDEs only take these through their settings UI.
- **zed** — prints how to install the qkt Zed extension from Zed's Extensions page, or as a dev extension from the bundled `share/editor/zed`.

Every target is idempotent: re-running it upgrades the files, and managed blocks in your own
config files are replaced, never duplicated.

## Generated grammars

```bash
qkt editor grammar --format textmate          # the VS Code / TextMate JSON grammar
qkt editor grammar --format vim               # the Vim/Neovim syntax file
qkt editor grammar --format tree-sitter       # editor/tree-sitter-qkt/grammar.js (Zed, Helix)
qkt editor grammar --format helix-highlights  # Helix highlight queries
qkt editor grammar --format zed-highlights    # Zed highlight queries
```

Every file under `editor/` they name is the output of these commands; a test asserts the checked-in
copies equal the generator's output, so they cannot drift from the parser. The grammar knows
every keyword by category (sections, actions, clauses, portfolio keywords), every registered
indicator and function name, the constants, the pseudo-symbols (`ACCOUNT`, `POSITION`, `NOW`,
`EXIT`, ...) and their member fields; strings are single-line with only the lexer's five escapes,
and `=` and `<>` are highlighted as operators.

## Install for everything detected

```bash
qkt editor install all
```

Skips editors that aren't detected. To install for an editor that isn't installed yet (so files are in place when you do install it), name it explicitly.

## Plugin-manager guard

If you use `lazy.nvim`, `packer`, or `vim-plug`, sideloading the qkt plugin into `~/.config/nvim/` bypasses your plugin manager. `qkt editor install nvim` detects this and warns:

```
qkt: detected plugin manager(s) in your Neovim config: lazy.nvim
     A sideloaded install bypasses your plugin manager — recommended snippets:
       lazy.nvim:
       { "elitekaycy/qkt", ft = "qkt",
         config = function(p) vim.opt.rtp:append(p.dir .. "/editor/nvim") end }
     Continue with sideload anyway? [y/N]
```

Default answer is no. Pass `--yes` to bypass the prompt in scripts. The check applies to `nvim` and `vim` targets only — VSCode has its own extension mechanism that doesn't conflict.

## Uninstall

```bash
qkt editor uninstall vscode
qkt editor uninstall nvim
```

For `vscode` this runs `code --uninstall-extension elitekaycy.qkt`. For `nvim`/`vim` it consults `~/.config/qkt/editor-install.json` (the install manifest qkt writes when it places files) and removes exactly those paths — never anything you wrote yourself. If you copied files manually before, `uninstall` refuses with a pointer to remove them yourself.

## How the manifest works

`~/.config/qkt/editor-install.json` records each install:

```json
{
  "installs": [
    {
      "target": "NVIM",
      "files": [
        "/home/you/.config/nvim/ftdetect/qkt.vim",
        "/home/you/.config/nvim/ftplugin/qkt.vim",
        "/home/you/.config/nvim/syntax/qkt.vim"
      ],
      "installedAt": 1734081600000
    }
  ]
}
```

Re-running `install` for the same target overwrites the entry; the install is idempotent and works as an upgrade path when you update qkt.

## Language server (diagnostics, completion, hover)

The `qkt` CLI is itself the language server. It speaks the Language Server Protocol over stdin/stdout:

```bash
qkt lsp
```

Any editor with an LSP client can talk to it — point the client's command for the `qkt` filetype at `qkt lsp`. Because the server is the same binary that runs your strategies, there is no second grammar to drift:

- **Diagnostics** run the parser and then the compiler, exactly like `qkt parse`. A syntax error
  and a compile error (unknown indicator, function, stream alias or reference, wrong arity, a
  bracket or sizing rule, a recursive `LET`) both arrive with the line and column of the
  offending token; when the compiler cannot pin an identifier it falls back to the rule's `WHEN`
  line, then to the section keyword.
- **Completion** offers the keywords that fit the position, every registered indicator, function
  and constant, the declared stream, basket and series aliases with their fields (`.close`,
  `.candle`, `.tick`, the instrument meta fields), and the members of every pseudo-symbol after
  its dot — `POSITION.btc.`, `NOW.`, `ACCOUNT.`, `EXIT.`, `STREAK.`, `TRADES.`, `COOLDOWN.`,
  `SEQUENCE.<name>.` — from the one vocabulary table the parser and compiler share.
- **Hover** documents every keyword, and every indicator's signature shown in hover is itself
  compiled by a test.

### Neovim (automatic)

`qkt editor install nvim` ships an ftplugin that autostarts the server on Neovim 0.8+ whenever you open a `.qkt` file and `qkt` is on your `PATH` — no extra config. One server is shared across all `.qkt` buffers in a project. Opt out with `let g:qkt_no_lsp = 1` (for example, if you prefer to configure qkt through nvim-lspconfig yourself).

To wire it by hand instead:

```lua
vim.lsp.start({ name = "qkt", cmd = { "qkt", "lsp" }, root_dir = vim.fn.getcwd() })
```

### Helix (automatic)

`qkt editor install helix` writes everything: highlighting from the tree-sitter grammar,
completion, hover and live errors from `qkt lsp`. Check with `hx --health qkt`.

### Emacs (automatic)

`qkt editor install emacs` installs `qkt-mode`: highlighting, comments (`M-;` uses `--`), and
eglot started against `qkt lsp` when `qkt` is on `exec-path`, for completion, hover and
flymake diagnostics. Turn the server off with `(setq qkt-enable-lsp nil)`.

### Sublime Text

`qkt editor install sublime` installs the grammar. For completion and diagnostics, install
the LSP package and add the client the installer prints to `LSP.sublime-settings`.

### JetBrains IDEs

`qkt editor install intellij` prints the steps with your paths filled in: add the bundled
`share/editor/vscode` folder under **Settings > Editor > TextMate Bundles**, then add a
language server in LSP4IJ with the command `qkt lsp` mapped to `*.qkt`.

### Zed (automatic)

The qkt Zed extension ([`editor/zed`](https://github.com/elitekaycy/qkt/tree/dev/editor/zed))
highlights with the tree-sitter grammar and starts `qkt lsp` itself. Install it from
**zed: extensions**, or before it is listed, with **zed: install dev extension** pointed at
`share/editor/zed`.

### VS Code, Cursor, Windsurf, VSCodium (automatic)

```bash
qkt editor install vscode     # or cursor, windsurf, codium
```

The extension carries its own language client, so colours, snippets, completion,
suggestions, hover and live errors all work as soon as it is installed. It starts
`qkt lsp` the first time you open a `.qkt` file, finding `qkt` through the `qkt.path`
setting, then `$QKT_HOME/bin`, then `PATH` (on Windows it resolves `qkt.exe` or
`qkt.cmd` through `PATHEXT`). If `qkt` cannot be found, highlighting and snippets keep
working and a notification offers the download link or the setting.

| Setting | Default | What it does |
|---|---|---|
| `qkt.path` | empty | Path to the `qkt` executable |
| `qkt.languageServer.enabled` | `true` | Turn the language server off, keeping highlighting |

**qkt: Restart language server** in the command palette restarts it after you upgrade qkt.

Projects created with `qkt create template` include `.vscode/extensions.json`, so VS Code
and its forks offer to install the extension when the project opens. Release builds
publish it to the VS Code Marketplace and to Open VSX (which Cursor, Windsurf and
VSCodium search).

## Haskell fallback

Where no qkt integration is installed, `.qkt` files can still be coloured by borrowing
Haskell, whose grammar colours qkt's UPPERCASE keywords, `--` comments, numbers, operators and
strings. GitHub does this through `.gitattributes` ([details](github-syntax-highlighting.md)).
For gists and for editors that read modelines, start the file with:

```text
-- -*- mode: haskell -*- vim: set ft=haskell:
```

The qkt parser reads the line as a comment. Vim, Neovim and Emacs without qkt installed use
Haskell; with qkt installed, the qkt plugin and `qkt-mode` take the buffer back, so the real
grammar and the language server still win.

## Resource use

One server runs per editor window, and only once a `.qkt` file is open. It analyses a
document once per pause in typing (200 ms), on a single background thread, rather than
on every keystroke, so typing fast never costs more than one core.

The `qkt` launcher itself starts `qkt lsp` in a small JVM (serial GC, capped 256 MB heap,
one JIT thread); a typical session sits near 60 MB. Because the launcher does this, no
editor integration carries JVM flags: any editor that runs `qkt lsp` gets the same small
server. Add flags with `QKT_OPTS` if you need to. The Windows `qkt.exe` from the release zip
runs the server with qkt's default JVM options.

## What's not here

Go-to-definition, rename, formatting and semantic tokens are out of scope for now
([design](../superpowers/2026-11-01-editor-support-design.md)); the Vim syntax file generated by `qkt editor grammar` is
what Neovim highlights with.
