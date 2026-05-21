---
name: issue-flow
description: Use when creating, labeling, triaging, closing, or filtering GitHub issues in the qkt repository, or when deciding what to work on next. Defines the issue lifecycle, the issue body format, and the full label taxonomy.
---

# qkt — issue flow

Every unit of work in qkt is tracked as a GitHub issue. The flow is fixed:

**find → open an issue → solve → close.**

You find a bug, pick up a backlog item, or scope a feature → an issue holds the
problem statement and the proposed solution → you solve it on a branch → the
merged PR closes the issue. `docs/backlog.md` stays the tiered roadmap; issues
are the per-item tracker. The two are kept in sync.

This skill is the source of truth for **how** issues are written and labeled.
For branching/commit/PR conventions, see the `qkt` skill.

---

## 1. When to open an issue

Open one whenever work is identified and not done in the same sitting:

- A bug found in the engine, DSL, or ops.
- A backlog item being promoted to active work.
- A feature or phase being scoped.
- A deferred follow-up ("fix or delete when this path is next touched").

A trivial fix made and merged immediately does not need an issue. Anything that
outlives the current change does.

---

## 2. Issue body format

Every issue body has exactly two sections, written so an average reader — not
just the author — can understand it cold:

```markdown
## Problem

<Plain-language explanation of what is wrong or missing and why it matters.
No unexplained jargon. A teammate who has never seen this code should follow it.>

## Proposed solution

<The concrete approach. Specific enough to act on; not a full design doc.
Use a checklist if the issue has distinct sub-parts.>

---
Backlog: `docs/backlog.md` — <tier/section>. <Epic/dependency links if any.>
```

Rules:
- The **Problem** explains the *what* and *why*, never the fix.
- The **Proposed solution** is a direction, not a spec. Specs live in
  `docs/superpowers/specs/` for phase work.
- Always footer-link the backlog section the item came from.
- An issue with sub-parts uses a `- [ ]` checklist in the solution.

---

## 3. Label taxonomy

Every issue carries **one priority**, **one effort**, and **at least one type**.
Flags are added as they apply.

### Priority — what to work on, and when

| Label | Meaning |
|---|---|
| `P0` | Current focus / blocks going live with real money |
| `P1` | Near-term — ops, config, staged branches, telegram completion |
| `P2` | Engine phases and parity gaps — real work, not blocking go-live |
| `P3` | Future — performance, asset-class expansion, platform maturity |

### Effort — how big the job is

| Label | Meaning |
|---|---|
| `effort: simple` | Small, low-risk, well-scoped — a good pick-up |
| `effort: moderate` | A normal feature or fix, roughly 1-3 days |
| `effort: advanced` | Deep, design-heavy engine work, multi-day |

### Type — what kind of work it is

| Label | Meaning |
|---|---|
| `bug` | A defect in existing behavior |
| `enhancement` | New behavior or capability |
| `documentation` | Docs, KDoc, changelogs |
| `ops` | Operational / config / CI, no engine code |
| `tooling` | Editor / developer tooling for `.qkt` files |
| `epic` | Large multi-part effort — body holds a checklist of child issues |

### Flags

| Label | Meaning |
|---|---|
| `parked` | Not ready to be picked up — deferred until prerequisites land. Stays visible; do not assign or start it. |
| `breaking-change` | Changes existing behavior, API, or config in an incompatible way |

GitHub cannot hard-lock an issue from assignment — `parked` is the signal. A
`parked` issue is off-limits until the label is removed.

---

## 4. Creating an issue

```bash
gh issue create \
  --title "<concise, specific — what is wrong or what to build>" \
  --label P2 --label enhancement --label "effort: moderate" \
  --body "$(cat <<'EOF'
## Problem

...

## Proposed solution

...

---
Backlog: `docs/backlog.md` — Tier 4 (single-phase engine work)
EOF
)"
```

Then cross-link it: append the issue link to its line in `docs/backlog.md`
(`([#NN](https://github.com/elitekaycy/qkt/issues/NN))`), and add it to the
parent epic's checklist if it has one.

---

## 5. Deciding what to work on — filtering

```bash
# The current focus — what blocks going live
gh issue list --label P0

# Near-term work, easiest first
gh issue list --label P1 --label "effort: simple"

# A quick win anywhere — small and not deferred
gh issue list --label "effort: simple" --search '-label:parked'

# Engine work that isn't a heavy lift
gh issue list --label P2 --label "effort: moderate"

# Everything deferred (do not pick these up)
gh issue list --label parked

# An epic and its surface
gh issue view <epic-number>
```

Priority answers *when*; effort answers *how big*. Pick the lowest-numbered
priority you can act on, then the smallest effort within it.

---

## 6. Closing the loop

When the work merges:

1. The PR closes the issue — put `Closes #NN` in the PR body, or close it by hand.
2. Flip the item's marker in `docs/backlog.md` from `tbd`/`progress` to `done`.
3. If the issue is a child of an epic, tick its box in the epic body.
4. When every child of an epic is done, close the epic.

---

## 7. Epics

A large effort that decomposes into independently shippable pieces gets an
`epic` issue. Its body lists the children as a checklist:

```markdown
## Proposed solution

Decomposed into separate, independently shippable pieces:

- [ ] #82 — foundation piece (must come first)
- [ ] #83 — next piece
- [ ] ...

Order: <state the dependency order between children>.
```

Children link back to the epic in their footer (`Part of the … epic #NN`).
The epic itself carries the priority/effort of the overall effort.
