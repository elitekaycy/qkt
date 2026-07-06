# Repository Instructions

This file is the root instruction file for automated coding work in this repository.
Keep it synchronized with `.claude/skills/qkt/SKILL.md`; that skill remains the
long-form convention reference.

## Mission

Build a deterministic, testable, event-driven trading runtime in Kotlin. Backtest
and live execution should share the same pipeline unless a divergence is explicit
and documented. Code, docs, commits, and process should be readable by a future
open-source contributor coming in cold.

## Branching And Promotion

Changes flow one way: `feature -> dev -> testing -> main`.

- Branch from `dev` for feature/fix/refactor work.
- Do not commit directly to `dev`, `testing`, or `main`; use PRs.
- `testing` is promoted from `dev` after essentials CI.
- `main` is promoted from `testing` after integration CI.
- Keep one concern per branch.

## Commit And PR Rules

- Use Conventional Commit subjects only: `<type>(<scope>): <subject>`.
- No commit body, no footer, no tool attribution, no emoji.
- Allowed source scopes follow `com.qkt.*`: `common`, `marketdata`,
  `execution`, `strategy`, `broker`, `engine`, `risk`, `backtesting`, `dsl`,
  `app`, `research`. Non-source scopes include `build`, `ci`, `docs`,
  `scripts`, and `skill`.
- Use imperative, lowercase subjects, max 70 chars, no trailing period.
- PRs need a clear summary, changes, tests, docs, compatibility, out-of-scope,
  and risk notes.

## Architecture Invariants

- Event-driven: strategies do not call brokers directly; behavior flows through
  the engine pipeline and event bus.
- Deterministic by default: inject `Clock`, seeded randomness, and deterministic
  ID/sequence generation. Avoid wall-clock or random calls in production logic.
- Backtest/live symmetry is explicit. If wiring changes in live, check replay,
  and document any intentional divergence.
- Translate external systems at one boundary per venue. Do not scatter unit,
  time-base, or cost normalization across call sites.
- One writer per derived quantity such as equity, positions, realized PnL, and
  marks.
- Keep blocking I/O and unbounded work off hot paths. Disk and network work
  belong on worker threads or async broker paths.

## Kotlin And Domain Style

- Match surrounding code style. Prefer simple, readable Kotlin.
- Use `val` by default, sealed hierarchies for closed models, data classes for
  values, nullable types instead of `Optional`, and `require`/`error` for
  validation/invariants.
- Do not use wildcard imports, semicolons, Java-style getters/setters, or `!!`
  in production code.
- Public API needs KDoc: new public types, top-level functions, interfaces, and
  externally callable methods get useful API documentation.
- Money math uses `BigDecimal`/project money helpers. Do not introduce `Double`
  for monetary values.
- UTC epoch milliseconds use `Long` and `Ms` suffixes where applicable.

## Hot Path Rules

The hot path is tick/bar ingest through strategy, signal, order, broker, fill,
risk, and accounting dispatch.

- State the cost of hot-path changes in PR risk notes.
- Avoid per-event allocation, broad scans, per-tick logging, blocking I/O, and
  recompute-from-scratch loops.
- Key active work by symbol/account/strategy rather than scanning historical or
  global state.
- Preserve parity first; speed changes need tests and evidence.

## Testing

- Use JUnit 5 and AssertJ.
- Tests should read like behavior specs.
- Prefer real project types and small anonymous objects over mocking libraries.
- Do not delete or disable failing tests without an issue-backed reason.
- Every production class with behavior should have focused tests.
- Run targeted tests while working, then run the appropriate pre-push checks.

## Required Checks

Before pushing:

```bash
./gradlew build
./gradlew test
git status
git log --oneline <base>..HEAD
grep -rEn 'TODO|FIXME|XXX' src/ || true
```

For Kotlin compiler instability in this workspace, prefer:

```bash
./gradlew <tasks> -Pkotlin.compiler.execution.strategy=daemon
```

Use `./scripts/precheck.sh` when appropriate.

## Documentation

- Meaningful features should have specs/plans under `docs/superpowers/` unless
  they are narrow issue-driven fixes.
- Public behavior changes need docs or KDoc updates in the same PR.
- Claims about parity, fidelity, or production readiness need linked tests or
  should be explicitly marked unproven.

## Local Hygiene

- Use `rg` for search.
- Do not revert unrelated local changes.
- Do not stage unrelated files. In particular, treat unknown untracked files as
  user-owned unless the task explicitly includes them.
