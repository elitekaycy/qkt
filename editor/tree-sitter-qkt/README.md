# tree-sitter-qkt

The tree-sitter grammar for qkt strategy files, used by Zed and Helix (and any other
tree-sitter host).

It is a highlighting grammar: a file is a flat sequence of keywords (one node type per
keyword category), calls, member accesses, `BROKER:SYMBOL` references, literals, operators
and identifiers. It never rejects a half-typed file; real parsing and errors come from the
language server, `qkt lsp`.

Nothing here is hand-written. `grammar.js` and `queries/highlights.scm` are generated from
the lexer's keyword table and the indicator, function and constant registries, and `src/`
is generated from `grammar.js`:

```bash
qkt editor grammar --format tree-sitter      > editor/tree-sitter-qkt/grammar.js
qkt editor grammar --format helix-highlights > editor/tree-sitter-qkt/queries/highlights.scm
qkt editor grammar --format zed-highlights   > editor/zed/languages/qkt/highlights.scm
cd editor/tree-sitter-qkt && npx tree-sitter-cli@0.24.7 generate
```

`GrammarFilesTest` fails when a checked-in file differs from the generator, and the `editor`
workflow fails when `src/` differs from `tree-sitter generate` or when any `.qkt` file in the
repository parses with an error.
