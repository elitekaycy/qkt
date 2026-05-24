# `.qkt` TextMate Grammar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a TextMate grammar that colors `.qkt` strategy files in VSCode, Sublime, IntelliJ (TextMate Bundles), `bat`, GitHub web, and other TextMate-compatible editors. Manual verification only (no CI tests).

**Architecture:** A single hand-authored JSON grammar at `editor/textmate/qkt.tmLanguage.json` with a `repository` block of named sub-patterns referenced from a top-level `patterns` array. Matching order: comments → strings → numbers → constants → keywords (section/flow/other) → operators → stream prefix. Eyeball-verified against `examples/hedge-straddle/hedge-straddle.qkt` in VSCode.

**Tech Stack:** TextMate grammar JSON, Oniguruma regex, manual VSCode "Inspect Editor Tokens and Scopes" verification.

**Spec:** `docs/superpowers/specs/2026-05-24-qkt-textmate-grammar-design.md`
**Branch:** `editor-textmate-grammar` (already created)
**Issue:** [#83](https://github.com/elitekaycy/qkt/issues/83)

---

## Task 1: Bootstrap JSON + comments + strings

**Files:**
- Create: `editor/textmate/qkt.tmLanguage.json`

- [ ] **Step 1: Create the grammar file with skeleton, comments, and strings**

Write `editor/textmate/qkt.tmLanguage.json`:

```json
{
  "name": "qkt",
  "scopeName": "source.qkt",
  "fileTypes": ["qkt"],
  "patterns": [
    { "include": "#comments" },
    { "include": "#strings" }
  ],
  "repository": {
    "comments": {
      "patterns": [
        {
          "name": "comment.line.double-dash.qkt",
          "match": "--.*$"
        },
        {
          "name": "comment.line.number-sign.qkt",
          "match": "#.*$"
        },
        {
          "name": "comment.block.qkt",
          "begin": "/\\*",
          "end": "\\*/"
        }
      ]
    },
    "strings": {
      "patterns": [
        {
          "name": "string.quoted.double.qkt",
          "begin": "\"",
          "end": "\"",
          "patterns": [
            { "name": "constant.character.escape.qkt", "match": "\\\\." }
          ]
        },
        {
          "name": "string.quoted.single.qkt",
          "begin": "'",
          "end": "'",
          "patterns": [
            { "name": "constant.character.escape.qkt", "match": "\\\\." }
          ]
        }
      ]
    }
  }
}
```

- [ ] **Step 2: Verify JSON syntactic validity**

Run: `jq . editor/textmate/qkt.tmLanguage.json > /dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add editor/textmate/qkt.tmLanguage.json
git commit -m "feat(editor): bootstrap textmate grammar with comments and strings"
```

---

## Task 2: Numbers + durations + booleans

**Files:**
- Modify: `editor/textmate/qkt.tmLanguage.json`

- [ ] **Step 1: Add `numbers` and `constants` patterns to `repository`, and include them**

Update `editor/textmate/qkt.tmLanguage.json`. The `patterns` array becomes:

```json
"patterns": [
  { "include": "#comments" },
  { "include": "#strings" },
  { "include": "#numbers" },
  { "include": "#constants" }
],
```

Add to `repository` (after `strings`):

```json
"numbers": {
  "patterns": [
    {
      "name": "constant.numeric.duration.qkt",
      "match": "\\b\\d+[smhd]\\b"
    },
    {
      "name": "constant.numeric.qkt",
      "match": "\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b"
    }
  ]
},
"constants": {
  "patterns": [
    {
      "name": "constant.language.qkt",
      "match": "\\b(TRUE|FALSE)\\b"
    }
  ]
}
```

Note: duration pattern must come before plain number — `\b\d+[smhd]\b` is more specific. Order in TextMate first-match-wins.

- [ ] **Step 2: Verify JSON syntactic validity**

Run: `jq . editor/textmate/qkt.tmLanguage.json > /dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add editor/textmate/qkt.tmLanguage.json
git commit -m "feat(editor): add numeric, duration, and boolean patterns"
```

---

## Task 3: Section and flow keywords

**Files:**
- Modify: `editor/textmate/qkt.tmLanguage.json`

- [ ] **Step 1: Add `section-keywords` and `flow-keywords` patterns**

Update top-level `patterns`:

```json
"patterns": [
  { "include": "#comments" },
  { "include": "#strings" },
  { "include": "#numbers" },
  { "include": "#constants" },
  { "include": "#section-keywords" },
  { "include": "#flow-keywords" }
],
```

Add to `repository`:

```json
"section-keywords": {
  "patterns": [
    {
      "name": "keyword.control.section.qkt",
      "match": "\\b(STRATEGY|VERSION|DEFAULTS|SYMBOLS|LET|RULES|PORTFOLIO|IMPORT)\\b"
    }
  ]
},
"flow-keywords": {
  "patterns": [
    {
      "name": "keyword.control.flow.qkt",
      "match": "\\b(WHEN|THEN|FOR|EACH|IN|DO|AS|RUN|HOLD|CASE|ELSE|END|SINCE)\\b"
    }
  ]
}
```

- [ ] **Step 2: Verify JSON syntactic validity**

Run: `jq . editor/textmate/qkt.tmLanguage.json > /dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add editor/textmate/qkt.tmLanguage.json
git commit -m "feat(editor): add section and flow keyword scopes"
```

---

## Task 4: Other keywords

**Files:**
- Modify: `editor/textmate/qkt.tmLanguage.json`

- [ ] **Step 1: Add `other-keywords` pattern**

Update top-level `patterns`:

```json
"patterns": [
  { "include": "#comments" },
  { "include": "#strings" },
  { "include": "#numbers" },
  { "include": "#constants" },
  { "include": "#section-keywords" },
  { "include": "#flow-keywords" },
  { "include": "#other-keywords" }
],
```

Add to `repository`:

```json
"other-keywords": {
  "patterns": [
    {
      "name": "keyword.other.qkt",
      "match": "\\b(BUY|SELL|CLOSE_ALL|CLOSE|CANCEL_ALL|CANCEL|LOG|WARN|ERROR|DEBUG|STACK_AT|STACK|SPACING|WITHIN|MFE|MARKET|LIMIT|STOP_LOSS|STOP|TRAILING|BRACKET|OCO_ENTRY|OCO|ORDER_TYPE|TAKE_PROFIT|TAKE|PROFIT|LOSS|RR|AT|BY|PCT|SIZING|RISK|USD|OF|EQUITY|BALANCE|POSITION_AVG_PRICE|POSITION|OPEN_ORDERS|ENTRY_QTY|TIF|GTC|IOC|FOK|DAY|GTD|EVERY|CROSSES|ABOVE|BELOW|BETWEEN|OPEN|MAX|MIN|MEAN|SUM|ACCOUNT|SYMBOL|NOW)\\b"
    }
  ]
}
```

Alphabetical order is wrong for prefix-collisions like `STACK_AT`/`STACK`, `STOP_LOSS`/`STOP`, `CLOSE_ALL`/`CLOSE`, `CANCEL_ALL`/`CANCEL`, `TAKE_PROFIT`/`TAKE`, `OCO_ENTRY`/`OCO`, `POSITION_AVG_PRICE`/`POSITION`. The pattern above puts the longer form first. Even though `\b...\b` correctly handles overlap (because `_` is a word character, `\bSTOP\b` doesn't match inside `STOP_LOSS`), keeping longer-first is defensive against future engines that don't strictly honor word boundaries.

Token count: 61 (matches §4.3 of the spec).

- [ ] **Step 2: Verify JSON syntactic validity**

Run: `jq . editor/textmate/qkt.tmLanguage.json > /dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3: Verify token count**

Run:
```bash
jq -r '.repository."other-keywords".patterns[0].match' editor/textmate/qkt.tmLanguage.json \
  | grep -oE '\b[A-Z_]+\b' \
  | sort -u \
  | wc -l
```
Expected: `61`

- [ ] **Step 4: Commit**

```bash
git add editor/textmate/qkt.tmLanguage.json
git commit -m "feat(editor): add action, order-type, sizing, tif, and builtin keyword scopes"
```

---

## Task 5: Operators + stream prefix

**Files:**
- Modify: `editor/textmate/qkt.tmLanguage.json`

- [ ] **Step 1: Add `operators` and `stream-prefix` patterns**

Update top-level `patterns`:

```json
"patterns": [
  { "include": "#comments" },
  { "include": "#strings" },
  { "include": "#numbers" },
  { "include": "#constants" },
  { "include": "#section-keywords" },
  { "include": "#flow-keywords" },
  { "include": "#other-keywords" },
  { "include": "#operators" },
  { "include": "#stream-prefix" }
],
```

Add to `repository`:

```json
"operators": {
  "patterns": [
    {
      "name": "keyword.operator.qkt",
      "match": "==|!=|<=|>=|<|>|\\+|-|\\*|/|%"
    },
    {
      "name": "keyword.operator.qkt",
      "match": "\\b(AND|OR|NOT)\\b"
    }
  ]
},
"stream-prefix": {
  "patterns": [
    {
      "name": "entity.name.namespace.qkt",
      "match": "\\b([A-Z][A-Z0-9_]*):"
    }
  ]
}
```

Stream-prefix comes last in `patterns`. By then all keywords are already matched, so `STRATEGY:` (if it ever appeared) would already be a section-keyword. The broker-prefix regex only fires on uppercase non-keyword identifiers followed by `:` — exactly the `EXNESS:XAUUSD` / `BACKTEST:BTCUSDT` shape.

- [ ] **Step 2: Verify JSON syntactic validity**

Run: `jq . editor/textmate/qkt.tmLanguage.json > /dev/null && echo OK`
Expected: `OK`

- [ ] **Step 3: Sanity-check the regex on the sample file**

This is a coarse smoke test — `grep -E` uses POSIX ERE, not Oniguruma, but it catches typos in the literal token list:

```bash
grep -cE '\b(BUY|SELL|STOP|LIMIT|STACK_AT|MFE|SIZING|TIF|GTD|NOW)\b' examples/hedge-straddle/hedge-straddle.qkt
```
Expected: a non-zero count (around 18 matches).

- [ ] **Step 4: Commit**

```bash
git add editor/textmate/qkt.tmLanguage.json
git commit -m "feat(editor): add operator and broker-prefix scopes"
```

---

## Task 6: Editor READMEs

**Files:**
- Create: `editor/README.md`
- Create: `editor/textmate/README.md`

- [ ] **Step 1: Write `editor/README.md`**

```markdown
# qkt editor tooling

Editor support files for `.qkt` strategy files.

## What's here

- **`textmate/`** — TextMate grammar for syntax highlighting. Works in VSCode, Sublime Text, IntelliJ (via TextMate Bundles), `bat`, Atom, and any other TextMate-compatible editor. See [textmate/README.md](textmate/README.md) for installation.

## What's not here yet

- Tree-sitter grammar (structural parser for Neovim, Zed, Helix, modern GitHub highlighting) — follow-up issue.
- Language server (`.qkt` autocomplete, hover, diagnostics) — tracked in [#84](https://github.com/elitekaycy/qkt/issues/84).
- VSCode marketplace extension — tracked in [#85](https://github.com/elitekaycy/qkt/issues/85).
- Neovim plugin — tracked in [#86](https://github.com/elitekaycy/qkt/issues/86).

All of the above are part of the editor-tooling epic [#71](https://github.com/elitekaycy/qkt/issues/71).
```

- [ ] **Step 2: Write `editor/textmate/README.md`**

```markdown
# `.qkt` TextMate Grammar

Syntax highlighting for `.qkt` strategy files in TextMate-compatible editors.

## Scopes

| Scope | Highlights |
|---|---|
| `comment.line.double-dash.qkt` / `comment.line.number-sign.qkt` / `comment.block.qkt` | `-- foo`, `# foo`, `/* foo */` |
| `string.quoted.double.qkt` / `string.quoted.single.qkt` | `"foo"`, `'foo'` |
| `constant.numeric.qkt` / `constant.numeric.duration.qkt` | `1.5`, `30m` |
| `constant.language.qkt` | `TRUE`, `FALSE` |
| `keyword.control.section.qkt` | `STRATEGY`, `VERSION`, `RULES`, etc. |
| `keyword.control.flow.qkt` | `WHEN`, `THEN`, `FOR`, `EACH`, etc. |
| `keyword.other.qkt` | `BUY`, `SELL`, `LIMIT`, `STOP`, `SIZING`, `TIF`, etc. |
| `keyword.operator.qkt` | `==`, `>=`, `+`, `*`, `AND`, `OR`, `NOT` |
| `entity.name.namespace.qkt` | broker prefix in `EXNESS:XAUUSD` |

Keywords match uppercase only by design — the lexer is case-insensitive but every `.qkt` sample uses uppercase, and falling through unhighlighted is a soft style nudge.

## Installing in VSCode

Create a minimal local extension scaffold (not committed, dev-only):

```bash
mkdir -p ~/.vscode/extensions/qkt-syntax-local
cd ~/.vscode/extensions/qkt-syntax-local
ln -s /path/to/qkt/editor/textmate/qkt.tmLanguage.json qkt.tmLanguage.json
```

Create `~/.vscode/extensions/qkt-syntax-local/package.json`:

```json
{
  "name": "qkt-syntax-local",
  "publisher": "local",
  "version": "0.0.0",
  "engines": { "vscode": "^1.50.0" },
  "contributes": {
    "languages": [
      { "id": "qkt", "extensions": [".qkt"], "aliases": ["qkt"] }
    ],
    "grammars": [
      {
        "language": "qkt",
        "scopeName": "source.qkt",
        "path": "./qkt.tmLanguage.json"
      }
    ]
  }
}
```

Reload VSCode. Open any `.qkt` file. Use **Developer: Inspect Editor Tokens and Scopes** to verify scopes resolve.

## Installing in Sublime Text

Copy `qkt.tmLanguage.json` to `~/.config/sublime-text/Packages/User/qkt.sublime-syntax` (Sublime auto-converts on load).

## Installing in IntelliJ

Settings → Editor → TextMate Bundles → `+` → point to the `editor/textmate/` directory.

## Verification

The committed `screenshot.png` shows the expected coloring of `examples/hedge-straddle/hedge-straddle.qkt` in VSCode's default dark theme.

## Source of truth

The keyword regexes derive from `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. When a new keyword token lands there, update the corresponding bucket in `qkt.tmLanguage.json` and eyeball-verify.
```

- [ ] **Step 3: Commit**

```bash
git add editor/README.md editor/textmate/README.md
git commit -m "docs(editor): add install instructions for textmate grammar"
```

---

## Task 7: Verification screenshot

**Files:**
- Create: `editor/textmate/screenshot.png`

This task requires running VSCode locally — it's not fully automatable.

- [ ] **Step 1: Install the grammar locally**

Follow `editor/textmate/README.md` "Installing in VSCode" exactly. Symlink the grammar from this repo's working tree into `~/.vscode/extensions/qkt-syntax-local/`.

- [ ] **Step 2: Reload VSCode and open the sample**

```bash
code examples/hedge-straddle/hedge-straddle.qkt
```

If VSCode doesn't already associate `.qkt` with the new language, set it manually: bottom-right language picker → select "qkt".

- [ ] **Step 3: Spot-check scopes via Inspect Tokens**

Open **Developer: Inspect Editor Tokens and Scopes** (Ctrl+Shift+P → "Inspect Tokens"). Click these positions and verify:

| Token | Expected scope |
|---|---|
| `STRATEGY` (line 1) | `keyword.control.section.qkt` |
| `VERSION` (line 1) | `keyword.control.section.qkt` |
| `1` (line 1) | `constant.numeric.qkt` |
| `# Pending-mode...` (line 3) | `comment.line.number-sign.qkt` |
| `DEFAULTS` (line 15) | `keyword.control.section.qkt` |
| `SIZING` (line 16) | `keyword.other.qkt` |
| `0.20` (line 16) | `constant.numeric.qkt` |
| `EXNESS:` (line 20) | `entity.name.namespace.qkt` |
| `EVERY` (line 20) | `keyword.other.qkt` |
| `5m` (line 20) | `constant.numeric.duration.qkt` |
| `WHEN` (line 28) | `keyword.control.flow.qkt` |
| `NOW` (line 28) | `keyword.other.qkt` |
| `IN` (line 28) | `keyword.control.flow.qkt` |
| `BUY` (line 32) | `keyword.other.qkt` |
| `ORDER_TYPE` (line 32) | `keyword.other.qkt` |
| `STOP` (line 32) | `keyword.other.qkt` |
| `BRACKET` (line 33) | `keyword.other.qkt` |
| `TIF` (line 34) | `keyword.other.qkt` |
| `GTD` (line 34) | `keyword.other.qkt` |
| `STACK_AT` (line 35) | `keyword.other.qkt` |
| `MFE` (line 35) | `keyword.other.qkt` |
| `>=` (line 35) | `keyword.operator.qkt` |
| `WITHIN` (line 35) | `keyword.other.qkt` |
| `30m` (line 35) | `constant.numeric.duration.qkt` |

If any scope is wrong, fix the regex in `qkt.tmLanguage.json` and re-test before continuing.

- [ ] **Step 4: Capture screenshot**

Take a screenshot of the open file with full coloring visible. Save as `editor/textmate/screenshot.png`. Crop to remove sidebar/statusbar (just the code editor pane is enough).

- [ ] **Step 5: Commit**

```bash
git add editor/textmate/screenshot.png
git commit -m "docs(editor): add textmate grammar verification screenshot"
```

---

## Task 8: Tree-sitter follow-up issue + push + PR

**Files:** none in repo.

- [ ] **Step 1: Open the tree-sitter follow-up issue**

```bash
gh issue create \
  --title "Tree-sitter structural grammar for .qkt" \
  --label P2 --label tooling --label enhancement --label "effort: advanced" \
  --body "$(cat <<'EOF'
## Problem

The TextMate grammar shipped in #83 covers ~80% of editors but misses the structural-editing benefits tree-sitter gives modern editors (Neovim via `nvim-treesitter`, Helix, Zed, GitHub's syntax-aware highlighting). Without a tree-sitter grammar, those editors fall back to TextMate or no highlighting at all, and they lose folds, indent rules, and structural cursor movement.

## Proposed solution

Write a structural tree-sitter grammar at `editor/tree-sitter-qkt/grammar.js` that encodes the full `.qkt` DSL grammar — `strategy_decl`, `defaults_block`, `symbols_block`, `let_block`, `rules_block`, `portfolio_decl`, `import_decl`, `when_clause`, `then_clause`, `for_each`, `case_expr`, `order_action`, `bracket_block`, `stack_at_clause`, `oco_entry`, sizing/risk expressions, TIF clauses, and the expression sublanguage (arithmetic, comparison, dotted field access, function calls).

Includes:

- [ ] `grammar.js` with full DSL coverage (~1500 LOC, transcription of `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`)
- [ ] Highlight queries in `queries/highlights.scm` mapped to standard tree-sitter highlight groups
- [ ] Fold queries in `queries/folds.scm` for `STRATEGY/RULES/SYMBOLS/DEFAULTS/PORTFOLIO/WHEN/BRACKET/STACK_AT` blocks
- [ ] Indent queries in `queries/indents.scm`
- [ ] Corpus tests at `corpus/*.txt` running via `tree-sitter test` in CI
- [ ] CI step: `npm install tree-sitter-cli && tree-sitter test`

Effort: advanced, multi-session. The Kotlin parser is the spec — transcription, not research.

---
Backlog: \`docs/backlog.md\` — Future / editor tooling. Part of the editor-tooling epic #71. Carved out of #83 during design (`docs/superpowers/specs/2026-05-24-qkt-textmate-grammar-design.md`).
EOF
)"
```

Note the new issue number. Add it to epic #71's checklist by editing the epic body.

- [ ] **Step 2: Run essentials pre-push check**

```bash
./gradlew ktlintCheck
```
Expected: `BUILD SUCCESSFUL`. The grammar work doesn't touch Kotlin, so this is a sanity pass — should be quick.

(Per auto-memory "CI over local build" — skip the full `./gradlew build` and let CI verify on push.)

- [ ] **Step 3: Push the branch**

```bash
git push -u origin editor-textmate-grammar
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --base dev --title "[editor] feat(editor): add textmate grammar for .qkt files" --body "$(cat <<'EOF'
## Phase
Editor tooling (non-phase). Spec: `docs/superpowers/specs/2026-05-24-qkt-textmate-grammar-design.md`. Plan: `docs/superpowers/plans/2026-05-24-qkt-textmate-grammar.md`.

## Summary
Ships a TextMate grammar for `.qkt` files, covering all 87 keyword tokens, three comment styles, both string-quote styles, numbers and duration literals, operators, and the `EXNESS:XAUUSD` broker-prefix shape. Themable at medium granularity (12 scopes + 1 structural). Verified by eyeball against `examples/hedge-straddle/hedge-straddle.qkt` in VSCode — screenshot committed.

Tree-sitter structural grammar was carved out of #83 during design and tracked as a follow-up issue.

## Changes
- New `editor/textmate/qkt.tmLanguage.json` grammar with 9 pattern repositories (comments, strings, numbers, constants, section/flow/other keywords, operators, stream-prefix).
- New `editor/README.md` overview pointer.
- New `editor/textmate/README.md` with VSCode/Sublime/IntelliJ install instructions and scope catalogue.
- New `editor/textmate/screenshot.png` verification artifact.

## Tests
- No automated tests. The spec deliberately rejects `vscode-tmgrammar-test` to avoid a Node toolchain in this Gradle-only repo.
- Verification: manual eyeball test documented in `editor/textmate/README.md` and the plan; 24 scope assertions from `hedge-straddle.qkt` confirmed by hand before commit.
- JSON syntactic validity verified at each commit via `jq`.

## Docs
- Install instructions in `editor/textmate/README.md`.
- Top-level pointer `editor/README.md` cross-linking #71/#84/#85/#86 and the tree-sitter follow-up.
- Scope catalogue documented in the spec and the textmate README.

## Backwards compatibility
None. New top-level `editor/` directory; no existing code touched.

## Out of scope
- Tree-sitter structural grammar — new follow-up issue (carved out during design).
- VSCode/Sublime/IntelliJ extension packaging — #85.
- LSP features — #84.
- Marketplace publication — #85.

## Risk
Low. Pure data file. Worst case: an edge construct mis-colors in some themes — fixable in a follow-up commit, no production behavior touched.

Closes #83.
EOF
)"
```

- [ ] **Step 5: Watch CI**

```bash
gh pr checks --watch
```
Expected: green essentials CI on `dev`. The grammar doesn't touch Kotlin so the only signal is "no regression in the Kotlin build."

- [ ] **Step 6: Merge after CI green**

After approval (or self-merge per qkt 24-hour solo rule — exception: a docs-only / editor-only PR with low risk is mergeable sooner if the author judges so):

```bash
gh pr merge --merge --delete-branch
```

The `Closes #83` line in the PR body auto-closes the issue. Verify:

```bash
gh issue view 83 --json state -q .state
```
Expected: `CLOSED`.

- [ ] **Step 7: Tick the box on epic #71**

Edit issue #71's body to check off `#83`:

```bash
gh issue view 71 --json body -q .body > /tmp/epic-71-body.md
sed -i 's/- \[ \] #83/- [x] #83/' /tmp/epic-71-body.md
gh issue edit 71 --body-file /tmp/epic-71-body.md
```

---

## Plan self-review

**Spec coverage check:**
- §2 Goals (every TokenKind covered, themable, eyeball-verifiable on hedge-straddle.qkt) → Tasks 1–5 cover every token category; Task 7 is the eyeball verification.
- §3 Non-goals — none implemented. ✓
- §4.1 Grammar shape → Task 1 establishes the skeleton; Tasks 2–5 add `patterns`/`repository` entries.
- §4.2 Twelve scopes + 1 structural → Tasks 1–5 add all 13 scopes by name.
- §4.3 Keyword bucket mapping → Tasks 3–4 implement the buckets verbatim; Task 4 step 3 verifies the count.
- §4.4 Pattern detail → Each task transcribes the spec's regex exactly.
- §4.5 Source-of-truth invariant → Documented in Task 6's `editor/textmate/README.md`.
- §5 Verification → Task 7 implements the 24-row scope-check table.
- §6 File layout → Tasks 1, 6, 7 create exactly the four files specified.
- §7 Open questions → None remain.
- §9 Future / dependencies → Task 8 step 1 files the tree-sitter follow-up, step 7 ticks epic #71.

**Placeholder scan:** No TBDs, no "implement later," no "similar to Task N." All regex literals are present in full. ✓

**Type consistency:** Every scope name (`keyword.control.section.qkt`, `entity.name.namespace.qkt`, etc.) is used identically across all tasks and matches the spec table. ✓

**One gap fixed inline:** The plan originally lacked an epic-update step. Added as Task 8 Step 7.
