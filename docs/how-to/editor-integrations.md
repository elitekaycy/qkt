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

## Language server (diagnostics, completion, hover)

The `qkt` CLI is itself the language server. It speaks the Language Server Protocol over stdin/stdout:

```bash
qkt lsp
```

Any editor with an LSP client can talk to it — point the client's command for the `qkt` filetype at `qkt lsp`. Because the server is the same binary that runs your strategies, its diagnostics match `qkt parse` / `qkt run` exactly: there is no second grammar to drift.

### Neovim (automatic)

`qkt editor install nvim` ships an ftplugin that autostarts the server on Neovim 0.8+ whenever you open a `.qkt` file and `qkt` is on your `PATH` — no extra config. One server is shared across all `.qkt` buffers in a project. Opt out with `let g:qkt_no_lsp = 1` (for example, if you prefer to configure qkt through nvim-lspconfig yourself).

To wire it by hand instead:

```lua
vim.lsp.start({ name = "qkt", cmd = { "qkt", "lsp" }, root_dir = vim.fn.getcwd() })
```

### Helix

In `~/.config/helix/languages.toml`:

```toml
[[language]]
name = "qkt"
scope = "source.qkt"
file-types = ["qkt"]
language-servers = ["qkt"]

[language-server.qkt]
command = "qkt"
args = ["lsp"]
```

### Emacs (eglot)

```elisp
(add-to-list 'eglot-server-programs '(qkt-mode . ("qkt" "lsp")))
```

Define a `qkt-mode` (deriving from `prog-mode`, with `.qkt` added to `auto-mode-alist`) or reuse whichever major mode you open `.qkt` files in, then `M-x eglot`.

### Zed

Zed needs a small language extension to bind the `.qkt` file type; once bound, register a language server whose command is `qkt` with args `["lsp"]`.

### VS Code

The bundled extension gives you syntax highlighting and snippets today. Wiring the language client — so VS Code also gets diagnostics, completion, and hover from `qkt lsp` — ships with the marketplace extension (#85). Until then, use any LSP-capable editor above for the full feature set.

## What's not here yet

- **Marketplace publish** for VSCode, including the bundled language client (#85) — install the extension via this command for now.
- **Tree-sitter grammar** (#117) — Neovim users will get tree-sitter highlighting once it lands, superseding the hand-written syntax file shipped here.
