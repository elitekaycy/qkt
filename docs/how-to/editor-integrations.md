# Editor integrations — install via `qkt editor`

`qkt editor` installs the bundled editor integrations onto your machine without you having to clone the repo, copy files by hand, or build the VSCode extension yourself. The integrations themselves live under `editor/` in the source tree; the qkt distribution tarball ships them under `share/editor/`.

## See what's supported

```bash
qkt editor list
```

Prints every supported editor and whether it's detected on this machine:

```
qkt editor — bundled at: /home/you/.local/share/qkt/share/editor

Supported targets:
  vscode     VSCode         [detected]
  nvim       Neovim         [detected]
  vim        Vim            [not found]
  sublime    Sublime Text   [not found]
```

## Install for one editor

```bash
qkt editor install vscode
qkt editor install nvim
qkt editor install vim
qkt editor install sublime
```

What each does:

- **vscode** — runs `code --install-extension <bundled.vsix>`. If no `.vsix` is bundled (uncommon — release tarballs include one), falls back to `npx @vscode/vsce package` against the bundled source. If neither route works, prints the GitHub release URL.
- **nvim** — copies `qkt.vim` into `$XDG_CONFIG_HOME/nvim/{ftdetect,ftplugin,syntax}/`.
- **vim** — same, into `~/.vim/{ftdetect,ftplugin,syntax}/`.
- **sublime** — copies the TextMate grammar into the Sublime user packages folder (Linux or macOS).

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

Default answer is no. Pass `--yes` to bypass the prompt in scripts. The check applies to `nvim` and `vim` targets only — VSCode and Sublime have their own extension mechanisms that don't conflict.

## Uninstall

```bash
qkt editor uninstall vscode
qkt editor uninstall nvim
```

For `vscode` this runs `code --uninstall-extension elitekaycy.qkt`. For `nvim`/`vim`/`sublime` it consults `~/.config/qkt/editor-install.json` (the install manifest qkt writes when it places files) and removes exactly those paths — never anything you wrote yourself. If you copied files manually before, `uninstall` refuses with a pointer to remove them yourself.

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

## What's not here yet

- **Language server** (#84) — completion, hover, diagnostics. Tracked separately; this command will gain a `lsp` target once the LSP ships.
- **Marketplace publish** for VSCode — install via this command for now; marketplace publication is a separate piece of work.
- **Tree-sitter grammar** (#117) — Neovim users will get tree-sitter highlighting once it lands, supersing the hand-written syntax file shipped here.
