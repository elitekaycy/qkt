# Contributing to qkt

This document is the short version of the project's working agreement. The full version lives at `.claude/skills/qkt/SKILL.md` — read it once when you start contributing.

## Before you write code

1. There must be a **design spec** at `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`. If there isn't one, the change probably belongs in a separate "design first" PR.
2. There must be an **implementation plan** at `docs/superpowers/plans/YYYY-MM-DD-<topic>.md` for non-trivial features.
3. Both spec and plan get committed alongside the code that implements them.

Bug fixes do not require a spec or plan unless the bug reveals a design flaw.

## Branching

- `main` is the integration branch. Always green.
- Feature branches: `phase<N>-<short-feature-name>` (e.g. `phase2-event-bus`).
- Bugfix branches: `fix-<short-description>`.
- Refactor branches: `refactor-<short-description>`.
- One concern per branch.

## Commits

We use Conventional Commits with strict project rules. Format:

```
<type>(<scope>): <subject>
```

**Subject only. No body. No footer. No AI co-author lines. No emoji.**

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `chore`, `merge`.
Scopes: package names (`common`, `marketdata`, `execution`, `strategy`, `broker`, `engine`, etc.) or `build`, `ci`, `docs`, `scripts`, `skill`.

Examples:

```
feat(engine): add Engine orchestrator
fix(broker): reject zero-quantity orders
refactor(common): split Clock into separate file
docs: phase 2 design for event bus
```

See `.claude/skills/qkt/SKILL.md` §3 for the full ruleset.

## Pre-push checklist

Run before every push:

```bash
./scripts/precheck.sh
```

This runs:

1. `./gradlew build` — must pass
2. `./gradlew test` — must pass
3. `git status` — must be clean
4. A scan for common problems (TODO/FIXME without issue links)

You are still responsible for: reading every commit message in the branch, ensuring no AI references slipped in, and confirming the PR is ready for review.

## Pull requests

Title format: `[phase <N>] <type>(<scope>): <subject>`. Maximum 70 characters total.

Description follows the template in `.claude/skills/qkt/SKILL.md` §5.

Required sections:
- Phase + spec/plan links
- Summary (the why)
- Changes (what)
- Tests (what was added/updated)
- Backwards compatibility notes
- Out of scope (what was deferred and where it's tracked)
- Risk level

Merge with `--no-ff` and the merge commit message `merge: phase <N> <short-description>`. Delete the feature branch after merge.

## Code style

Headlines:

- File size aim: under 150 lines. Hard cap: 200 lines.
- One top-level concept per file (exception: tightly coupled types).
- No comments unless the **why** is non-obvious.
- Names describe **what**, never how or when. No `LegacyXxx`, `NewYyy`, `EnhancedZzz`.
- No emojis in code or files.
- No AI references anywhere.

For Kotlin idioms (data classes, sealed classes, null safety, Elvis, extension functions, etc.) see `.claude/skills/qkt/SKILL.md` §10.

## Testing

- JUnit 5 + AssertJ. No mocking frameworks.
- Test names are backtick-quoted sentences.
- Use real types. Use anonymous objects for one-off impls.
- Every production class with behavior has a dedicated test class.
- Tests are deterministic — seed every `Random`, inject every `Clock`.

Full standards in `.claude/skills/qkt/SKILL.md` §11.

## Architecture invariants

These hold regardless of phase. Violations require design discussion before merge.

- Event-driven. Strategies never call brokers directly.
- Deterministic by default. No `System.currentTimeMillis()`, no `UUID.randomUUID()`, no `Math.random()` in production code.
- Read/write split via the type system when components share state.
- Mock-first, real-second for every external integration.

Full list in `.claude/skills/qkt/SKILL.md` §7.

## Living document

This document and the project skill grow as the project grows. When a convention is missing or wrong, fix it in the same PR as the code change that motivated the fix, with commit type `docs(skill)`.
