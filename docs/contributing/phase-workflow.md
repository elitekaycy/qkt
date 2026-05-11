# Phase workflow

qkt is built in numbered **phases** — each phase ships a coherent slice of capability and gets a public changelog post-merge.

## The lifecycle

```text
brainstorm → spec → plan → implement → merge → changelog
```

| Step | Output | Lives in |
|---|---|---|
| Brainstorm | Design dialogue | conversation |
| Spec | What + why | `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` |
| Plan | How, in bite-sized tasks | `docs/superpowers/plans/YYYY-MM-DD-<topic>.md` |
| Implement | Code + tests | feature branch, one commit per task |
| Merge | `--no-ff` merge commit | `main` |
| Changelog | What's now available | `docs/phases/phase-<N>.md` |

## When to use the formal flow

**Always use spec + plan** for:

- New types or packages with nontrivial surface area
- Cross-package work
- Anything that touches the broker, DSL parser, or backtest engine
- New CLI commands

**Skip to direct implementation** for:

- Bug fixes
- ktlint-only changes
- Backlog item that's a single small commit
- Doc-only updates

When skipping the formal flow, still create explicit tasks (e.g. via TaskCreate in Claude Code) so progress is trackable.

## Phase numbering

Phases are sequential and never reused. Don't reorder them; if Phase 17 ships before Phase 16, that's fine — Phase 16 stays Phase 16.

The current state of every phase lives in [`docs/phases/`](../phases/index.md).

## What goes in a phase changelog

Per the [SKILL spec](https://github.com/elitekaycy/qkt/blob/main/.claude/skills/qkt/SKILL.md#phase-changelog-requirements):

1. **Summary** — 2-4 sentences on what + why.
2. **What's new** — bullet list of every new public surface.
3. **Migration from previous phase** — before/after for anything renamed or breaking.
4. **Usage cookbook** — multiple worked, copy-pasteable examples.
5. **Testing patterns** — canonical fakes, fixtures, assertion style.
6. **Known limitations** — what didn't ship and why.
7. **References** — spec link, plan link, merge commit SHAs.

Aim for 200-500 lines. Reader is a future contributor who hasn't read the spec.

## Backlog

Items scoped out of a phase land in [`docs/backlog.md`](../backlog.md). Anything blocking a future phase gets called out in that phase's spec.
