# Daemon resync design

## Status

Draft for implementation on `feat-daemon-resync`.

## Problem

Operators need a safe way to apply a changed strategy or portfolio artifact to a
running qkt daemon without restarting unrelated strategies and without manually
sequencing `stop`, file copy, `deploy`, `reconcile`, and rollback steps. The
current deploy command correctly goes through the daemon control plane, but it is
only a first-start operation: deploying an existing name returns a conflict.

The missing lifecycle operation is a transactional resync:

- validate the new artifact before touching live state;
- affect only the named strategy, portfolio, or portfolio child;
- preserve qkt's event-driven engine boundaries;
- reconcile broker state before the replacement accepts live ticks;
- leave an audit trail that can explain what changed and how to roll back.

## Goals

- Add `qkt resync` as the operator-facing command for replacing an already
  deployed strategy or portfolio artifact.
- Route resync through the daemon HTTP control plane so the running registry owns
  the change.
- Treat resync as a cold-path lifecycle transaction. It must not add work to the
  tick/bar hot path.
- Keep strategy behavior inside `LiveSession` and `TradingPipeline`. Resync must
  build a new session; it must not mutate compiled strategy code in a running
  engine thread.
- Preserve deployment identity while recording artifact generations.
- Support dry-run planning so operators can verify scope before applying.
- Update create templates and get-started docs so resync is part of the normal
  deploy lifecycle from day one.

## Non-goals

- No in-place hot reload of a running `TradingPipeline`.
- No migration of strategy-local in-memory indicator state between two compiled
  strategies. A resynced strategy starts the same way a freshly deployed strategy
  starts: from persisted execution state plus broker reconciliation and warmup.
- No automatic alias migration for renamed portfolio children in the first
  implementation. Renames are rejected as destructive until an explicit migration
  map exists.
- No remote network control-plane authentication changes. Existing control-plane
  loopback assumptions remain unchanged.

## Architecture

### Resync is a registry transaction

The daemon already owns deployment through `StrategyRegistry`, `ControlRoutes`,
and `StrategyHandle.Factory`. Resync belongs at that same boundary:

1. Parse and classify the replacement artifact.
2. Build a deterministic plan for the requested target.
3. If `--dry-run`, return the plan and stop.
4. If applying, acquire the registry mutation path for that target.
5. Create the replacement handle first when possible.
6. Stop and remove the old handle only after the new handle has passed startup
   reconciliation.
7. Commit the new handle under the same deployment name.
8. Journal the transaction.

This deliberately avoids mutating `LiveSession` internals. The event bus,
strategy callbacks, risk state, order manager, broker translator, insights, and
notifier wiring are created the same way as a normal deploy.

### Strategy replacement

For a standalone strategy, resync is a same-name replace:

```text
alpha generation 4 sourceSha=A
alpha generation 5 sourceSha=B
```

The public deployment name remains `alpha`. The new handle gets a fresh
`LiveSession`, fresh observability server, and the same daemon-level shared
dependencies. Startup reconciliation reattaches persisted/broker execution
state before the new session runs.

The first implementation is conservative: it builds the new handle before
closing the old handle. That minimizes downtime and proves the replacement can
start. Because both handles briefly share a deployment identity, this path is
only allowed after the old handle has been operator-halted for new entries. The
old session still owns protective management during the creation window.

### Portfolio replacement

Portfolio resync has two safe scopes:

- `qkt resync portfolio.qkt --as book`: replace the whole portfolio as one
  transaction. This stops the old supervisor and children only after the new
  portfolio can compile and deploy.
- `qkt resync book/child --from strategy.qkt`: replace one child while leaving
  siblings running. This is planned but may land after standalone and
  whole-portfolio resync if child replacement needs deeper supervisor surgery.

If a portfolio rule/capital file changes, the safe default is whole-portfolio
replacement because the supervisor is the writer for child gate flags and book
risk aggregation. If only an imported child source changes and its alias stays
stable, a child-only transaction can be valid.

### Reconciliation

Resync reuses the deploy-time reconciliation contract in `LiveSession`:

- broker position reads must succeed;
- persisted leg state must match broker truth unless the operator explicitly
  uses the existing `--reconcile=ignore-mismatches` policy;
- a reconcile mismatch returns `409` and leaves the old deployment active.

This is the critical trading-safety property. A new strategy must not start
from assumed state.

### Journaling

Every applied resync writes an operator journal record. Later iterations should
add a dedicated generation journal under `state/resync/`, but the first
implementation records enough for auditability:

- action: `resync`;
- target;
- replacement file;
- dry-run/apply;
- reconcile policy;
- affected deployment names;
- outcome.

### Templates and docs

`qkt create template` is the operator's first experience of daemon lifecycle.
Deploy templates must include resync in the generated Makefile and project
README. Get-started docs must show the normal update path:

```bash
qkt resync strategies/alpha.qkt --as alpha --dry-run
qkt resync strategies/alpha.qkt --as alpha
qkt reconcile alpha
```

Docker examples use:

```bash
docker compose exec qkt qkt resync /strategies/alpha.qkt --as alpha
```

## CLI contract

Standalone strategy or whole portfolio:

```bash
qkt resync <file.qkt> --as <name>
qkt resync <file.qkt> --as <name> --dry-run
qkt resync <file.qkt> --as <name> --json
qkt resync <file.qkt> --as <name> --reconcile=ignore-mismatches
```

Planned child form:

```bash
qkt resync <portfolio>/<child> --from <strategy.qkt>
```

`deploy` remains first-start only. Existing-name replacement must go through
`resync` so operator intent is explicit.

## Safety defaults

- Fail closed if the daemon is not running.
- Fail closed if the target is unknown.
- Fail closed if the replacement file does not parse.
- Fail closed on reconcile mismatch unless explicitly waived with the existing
  reconcile policy.
- Do not flatten by default.
- Do not stop unrelated strategies.
- Do not bypass production promotion gates.
- Do not weaken broker route or instrument validation.

## Backtest/live parity

Resync is a live daemon lifecycle operation, not strategy execution logic. It
does not change how strategy rules, order triggers, fills, risk, or PnL are
computed. Replacement sessions are built through the same `LiveSession` wiring
as deploy; backtest/live parity remains anchored in `TradingPipeline`.

Any future feature that persists additional strategy-local DSL state across a
resync boundary must document its replay semantics and add tests proving the
same boundary can be reproduced in replay.

## Hot-path cost

No tick/bar hot-path cost. Resync adds CLI parsing, control-plane routes,
registry mutation, parsing, reconciliation, and journaling in the cold deploy
path only.

## Risks

- Replacement sequencing can create a brief overlap where old and new handles
  exist. The implementation must halt old new-entry submission before creating
  the replacement and must commit only one handle under the deployment name.
- Whole-portfolio replacement can temporarily stop all children. Child-only
  resync should be designed separately if whole-book downtime is unacceptable.
- Rollback is only as good as generation metadata. The first implementation can
  record previous source paths; a later generation store should make rollback a
  first-class command.

## Acceptance

- `qkt resync <file> --as <name> --dry-run` returns a plan and does not mutate
  registry state.
- `qkt resync <file> --as <name>` replaces a deployed standalone strategy under
  the same name.
- Failed parse/reconcile leaves the old deployment running.
- Resync is available through the CLI and daemon control plane.
- The command appears in CLI help and reference docs.
- Deploy-oriented templates include resync commands.
- Get-started deploy docs show the dry-run/apply/reconcile workflow.
- Focused tests cover command parsing, control-client call shape, registry
  replacement success, dry-run non-mutation, and failure rollback.
