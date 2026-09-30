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
  nvim       Neovim         [detected]
  vim        Vim            [not found]
```

## Install for one editor

```bash
qkt editor install vscode
qkt editor install nvim
qkt editor install vim
```

What each does:

- **vscode** — runs `code --install-extension <bundled.vsix>`. If no `.vsix` is bundled (uncommon — release tarballs include one), falls back to `npx @vscode/vsce package` against the bundled source. If neither route works, prints the GitHub release URL.
- **nvim** — copies `qkt.vim` into `$XDG_CONFIG_HOME/nvim/{ftdetect,ftplugin,syntax}/`.
- **vim** — same, into `~/.vim/{ftdetect,ftplugin,syntax}/`.

There is no Sublime target: Sublime Text cannot load a TextMate JSON grammar, so the installer
does not write one. Use `qkt editor grammar --format textmate` with a converter of your choice.

## Generated grammars

```bash
qkt editor grammar --format textmate   # the VS Code / TextMate JSON grammar
qkt editor grammar --format vim        # the Vim/Neovim syntax file
```

Both files under `editor/` are the output of these commands; a test asserts the checked-in
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

The bundled extension gives you the generated syntax highlighting and snippets. It does not
start a language client; for diagnostics, completion and hover in VS Code, install a generic LSP
client extension and point it at `qkt lsp` for the `qkt` language, or use one of the editors
above.

## What's not here

Go-to-definition, rename, formatting, semantic tokens, a bundled VS Code language client and a
tree-sitter grammar are out of scope; the Vim syntax file generated by `qkt editor grammar` is
what Neovim highlights with.
