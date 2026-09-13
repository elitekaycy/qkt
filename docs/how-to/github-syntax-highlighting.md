# Highlight .qkt files on GitHub

GitHub colours source files with the grammars that
[Linguist](https://github.com/github-linguist/linguist) ships. qkt is not a Linguist language yet, so
GitHub shows a `.qkt` file as plain text unless you tell it which grammar to borrow. qkt borrows the
**Haskell** grammar: qkt's UPPERCASE keywords, `--` comments, operators, numbers and strings each get a
distinct colour.

| Where the file lives | What to do |
|---|---|
| Repository (public or private) | Add `.gitattributes` (projects from `qkt create template` already have it) |
| Gist | Put the modeline `-- -*- mode: haskell -*-` on the first line |
| Your editor | Install the real qkt grammar: [editor integrations](editor-integrations.md) |

## Repositories

Add this file at the repository root and push:

```text title=".gitattributes"
*.qkt linguist-language=Haskell linguist-detectable=false
```

- `linguist-language=Haskell` picks the grammar GitHub uses to colour `.qkt` files.
- `linguist-detectable=false` keeps `.qkt` out of the repository's language bar, so the repository is not
  reported as Haskell. Highlighting is unaffected.

Every project created with `qkt create template` includes this `.gitattributes`. For an existing repository,
copy the line above. GitHub applies it to files already in the repository as soon as the commit is pushed.

To check what Git will tell GitHub:

```bash
git check-attr -a -- strategies/my_strategy.qkt
# strategies/my_strategy.qkt: linguist-language: Haskell
# strategies/my_strategy.qkt: linguist-detectable: false
```

## Gists

Gists ignore `.gitattributes`. Linguist also reads editor modelines, so start the file with a qkt comment
that names the grammar:

```text
-- -*- mode: haskell -*-
STRATEGY ema_cross VERSION 1
...
```

The qkt parser treats the line as an ordinary comment, so the file still runs unchanged. Use the Emacs form
shown here rather than a Vim modeline (`vim: ft=haskell`): a Vim modeline would also switch Vim and Neovim
from the real qkt grammar to Haskell.

If you would rather not add a line, name the gist file with a `.hs` extension instead
(`ema_cross.qkt.hs`), at the cost of a file name that no longer ends in `.qkt`.

## What the colours cover

The Haskell grammar knows nothing about qkt, so the colours follow the shape of the code rather than its
meaning:

| qkt | Coloured as |
|---|---|
| UPPERCASE words: `STRATEGY`, `WHEN`, `THEN`, `BUY`, `BRACKET`, and broker/symbol names such as `EXNESS:XAUUSD` | constructor |
| `POSITION.` and other `UPPERCASE.` qualifiers | constant |
| `=`, `<=`, `!=`, `.`, `-`, `/` | operator |
| Numbers | constant |
| `--` comments and `"strings"` | comment, string |
| lowercase names: `lag`, `percentile_rank`, parameters | plain text |

SQL, the grammar qkt borrowed before, coloured only a handful of qkt keywords (`AND`, `TIMES`, `POSITION`).
The choice was made by rendering the same strategies under several grammars that use `--` comments and
counting the coloured tokens; Haskell and PureScript coloured the most, and Haskell also separates
`POSITION.` from keywords.

Keywords are not coloured by what they do (a rule keyword and an order keyword look the same). That needs
a qkt grammar inside Linguist, which Linguist accepts only once `.qkt` is used across a large number of
public repositories owned by different people.
