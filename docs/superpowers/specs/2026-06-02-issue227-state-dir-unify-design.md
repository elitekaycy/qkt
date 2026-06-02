# Unify daemon state persistence on `StateDir` (#227)

Status: design approved
Issue: #227 (P0, bug) — blocks #33 go-live; part of production-readiness umbrella #142

## Problem

The daemon's per-strategy persistence — pending orders, bracket pairs, OCO legs,
leg book, pending stacks — is written to `~/.qkt/state`. On prod that resolves to
`/home/qkt/.qkt/state`, which lives on the container's **ephemeral overlay
filesystem**, not the persistent `qkt-state` volume mounted at `/var/lib/qkt`.
Every container recreate (each deploy or crash) wipes it.

Restart recovery (`MT5StateRecovery`, OCO restart-linkage #46) depends on this
state. With it on throwaway storage, a deploy or crash mid-position orphans live
OCO legs and loses pending-order state — unacceptable for a real-money account.

### Root cause: two parallel state-dir primitives that disagree

The daemon resolves state location **twice**, two different ways:

- `StateDir` (`com.qkt.cli.daemon.StateDir`) — env-aware resolution
  (`--state-dir` override > `QKT_STATE_DIR` > `XDG_STATE_HOME/qkt` >
  `~/.local/state/qkt`). It owns `logs/`, `control.port`, `daemon.pid`, and
  *documents* a `stateRoot` (`<root>/state`) plus a `stateFor(name, file)` helper
  for strategy files. This is why logs correctly land on the volume at
  `/var/lib/qkt/logs` — `StateDir` reads `QKT_STATE_DIR`.

- `Config.statePersistor()` — the only real caller (`DaemonCommand:150`) — builds
  `FileStatePersistor(Path.of(Config.stateDir))`, where `Config.stateDir`
  resolves from the `state.dir` config key, defaulting to `~/.qkt/state`. It never
  reads `QKT_STATE_DIR`. Prod's config sets no `state.dir`, so the ephemeral
  default wins and the operator's `QKT_STATE_DIR=/var/lib/qkt` compose intent is
  silently ignored — the same dead-env-var trap as the known-dead `MT5_LOGIN`
  vars.

The startup line `[INFO] state directory: ${stateDir.root}` (`DaemonCommand:195`)
prints the `StateDir` root while state actually goes elsewhere — it is currently
**lying** about where strategy state lives.

### This is the original design, drifted

The Phase 29 spec
(`docs/superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md:85`,
264) specified `FileStatePersistor(stateDir: StateDir)` and the
`stateDir.stateFor(...)` helper. Implementation diverged: `FileStatePersistor`
took a raw `Path`, `Config` grew its own `state.dir` resolution, and
`stateFor`/`stateRoot` were built per the design but never wired into the
persistor (zero production callers; only a unit test references them). This fix
restores the intended wiring rather than inventing a new one.

## Goals

- Strategy state survives a container recreate on prod (the #33 unblock).
- One state-dir resolution for the whole daemon — no parallel primitives.
- The operator's `QKT_STATE_DIR` compose intent is honored.
- The resolved persistor root is visible in logs, not silent.

Out of scope: changing the backtest/replay path (it does not persist), the OCO
recovery logic itself (#46), and the separate `StateFileWriter` concurrent-write
race (#228, filed separately).

## Approach: route the persistor through `StateDir`

The daemon already resolves a `StateDir` at `DaemonCommand:51`, honoring
`QKT_STATE_DIR`. Feed its `stateRoot` into the persistor. `FileStatePersistor`'s
own `<rootDir>/<strategyId>/<file>` join then produces
`<QKT_STATE_DIR>/state/<strategy>/<file>` — exactly the layout `StateDir.stateFor`
documents — sitting beside `logs/` under the same volume-mounted root.

One resolution governs the whole daemon; the divergent `Config.stateDir` path is
removed.

### Code changes

1. **`Config`** (`src/main/kotlin/com/qkt/cli/Config.kt`)
   - Remove the `stateDir` property and all use of the `state.dir` config key.
   - `statePersistor()` → `statePersistor(stateRoot: Path)`: unchanged
     `enabled`/`async` policy (Noop when disabled; `FileStatePersistor(stateRoot)`
     when enabled; wrapped in `AsyncStatePersistor` when `async`). The directory
     is now supplied by the caller, not resolved here.
   - Update the `state`-block KDoc: drop the `dir` bullet; note the directory is
     the daemon state dir (`<stateDir>/state`), resolved by `StateDir`. Keep
     `enabled` and `async`.

2. **`DaemonCommand`** (`src/main/kotlin/com/qkt/cli/DaemonCommand.kt:150`)
   - `val statePersistor = cfg.statePersistor(stateDir.stateRoot)`.

3. **`StateDir`** (`src/main/kotlin/com/qkt/cli/daemon/StateDir.kt`)
   - Delete `stateFor(name, fileName)`. It is dead and reimplements
     `FileStatePersistor`'s `<root>/<strategy>/<file>` join in the wrong layer
     (persistence must not depend on `cli.daemon`). Keep `stateRoot` — now
     consumed by `DaemonCommand`.

4. **Startup log** (`DaemonCommand`)
   - Add `[INFO] strategy state: ${stateDir.stateRoot}` so the resolved persistor
     root is explicit. The existing `state directory` line becomes truthful.

### Tests

- `ConfigStateTest` — pass a root to the two `statePersistor(...)` calls; delete
  `state dir without ~ stays unchanged` (tests the removed `state.dir` behavior);
  drop the `c.stateDir` assertions. Add a behavior test: `statePersistor(root)`
  with `enabled=true`, save a leg book through it, assert the JSON file appears
  under `root/<strategy>/` — proving the passed root is honored (real write, no
  mocks).
- `StateDirStateForTest` — drop the two `stateFor` cases, keep the `stateRoot`
  case, rename the file to match (`StateDirStateRootTest`).

### Behavior change

The default local-dev state location moves from `~/.qkt/state` to
`<StateDir default>/state` (`~/.local/state/qkt/state`), aligning state with where
the daemon already keeps logs and control files. Prod is unaffected by the
default — it sets `QKT_STATE_DIR`, so state moves from the ephemeral
`~/.qkt/state` to the persistent `/var/lib/qkt/state`, which is the fix. The
`state.dir` config key is removed (breaking the config surface; acceptable per
qkt's no-backwards-compat posture, and prod does not set it).

## Verification (ops — operator-run, not code)

Prod already sets `QKT_STATE_DIR=/var/lib/qkt`, which this change now honors, so
no prod config edit is required. After deploying the new image, during a no-OCO
window:

1. Confirm `/var/lib/qkt/state/<strategy>/*.json` is populated (was absent before).
2. Recreate the container and confirm the files survive.
3. Confirm the startup log prints `strategy state: /var/lib/qkt/state`.

Passing this verify is the concrete #33 unblock. Flipping demo→real remains a
separate, operator-owned decision.

## References

- Issue: #227. Umbrella: #142. Blocks: #33.
- Phase 29 spec: `docs/superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md`.
- Related (separate fix): #228 — `StateFileWriter` shared temp filename race.
