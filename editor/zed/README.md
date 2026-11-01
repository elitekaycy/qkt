# qkt for Zed

Highlighting from the [tree-sitter grammar](../tree-sitter-qkt/), and completion, suggestions,
hover and live diagnostics from `qkt lsp`, started automatically for `.qkt` files.

## Install

In Zed, run **zed: extensions**, search for **qkt** and click Install. Make sure `qkt` is on
the `PATH` Zed sees (the extension finds it with the worktree's shell environment).

Before the extension is listed, install it from this folder: **zed: install dev extension**,
then choose `editor/zed` (or `share/editor/zed` in a qkt release; `qkt editor install zed`
prints the path). Dev installs need Rust installed through rustup.

The server runs in a small JVM (capped heap, one GC and one JIT thread).

## Releasing

`[grammars.qkt].rev` in `extension.toml` must name a commit that contains the current grammar.
After a grammar change merges, bump `rev` and `version`, then open a pull request against
[zed-industries/extensions](https://github.com/zed-industries/extensions) that points its
`qkt` submodule at the new commit.
