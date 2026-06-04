# `qkt observe` — go-live verifier for a live strategy (#33) — Design

**Goal:** Turn the week-long manual log-watch that gates the hedge-straddle real-money switch into an
automated go/no-go report. A new read-only `qkt observe` subcommand queries the running daemon's
control plane and evaluates three gates over a chosen period.

**Why:** #33 gates the demo→real-money switch on three things being true over a clean week:
1. the `:55` UTC placement window fires correctly each scheduled window,
2. no engine-side errors in the daemon over the period,
3. daily PnL roughly matches a hand-computed expectation.

Today that is manual log-reading. This makes it a one-command report so the operator can run it daily
(or at week's end) and get a per-gate verdict instead of eyeballing logs.

## Context — what the daemon already exposes

The daemon runs an HTTP control plane on `127.0.0.1`, ephemeral port in `<state-dir>/control.port`,
spoken by `ControlClient` (`src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`):
- `GET /status/<name>` — JSON: cumulative `realized`, `unrealized`, `equity`, `balance`, `positions[]`,
  `lastTrade` (`StatusSnapshot`).
- `GET /logs/<name>?since=<ts>&lines=<n>` — the per-strategy log history (persists across restarts under
  `<state-dir>/logs/<strategy>.log`; SLF4J/Logback plain text, `[ERROR]`/`[WARN]`/`[INFO]` levels).
- `GET /metrics` — Prometheus text: `qkt_daemon_uptime_seconds`, `qkt_strategies_running`,
  `qkt_strategy_trades_total{strategy=...}`, notifier health.
- `GET /latency`, `/list`, `/health`.

So the verifier is **consume + evaluate**, not new instrumentation — with one small exception (gate 3,
below).

## Architecture & mechanism

- A new **read-only** CLI subcommand `qkt observe`, a *client* of the control plane (reuses
  `ControlClient`). It never mutates the daemon (no halt/stop/deploy).
- **One-shot and stateless.** It reconstructs the period from the persisted `/logs` plus a point-in-time
  `/status` and `/metrics` snapshot. No long-running monitor, no local state file (YAGNI on a continuous
  watcher — the logs already hold the history).
- **Targeting a remote daemon.** The daemon binds `127.0.0.1`, so prod is reached via an SSH tunnel:
  ```
  ssh -L 9999:127.0.0.1:<remote control.port> root@<prod>
  qkt observe --strategy hedge-straddle --since 7d --control-port 9999
  ```
  This needs one `ControlClient` addition: accept an explicit host/port instead of only reading the local
  `control.port` file. Default behavior (local `control.port`) is unchanged.

## CLI surface

```
qkt observe --strategy <name> [--since <dur|iso>] [--windows <schedule>]
            [--control-host <h>] [--control-port <p>] [--json]
```
- `--strategy` — required; the strategy/daemon-handle name (e.g. `hedge-straddle`).
- `--since` — observation window; duration (`7d`, `24h`) or ISO instant. Default `24h`.
- `--windows` — expected placement schedule. Default = hedge-straddle's `hours=[6,7,12,13,14,15] minute=55`;
  configurable (`--windows 6,7,12,13,14,15@55`) so the tool generalizes to other scheduled strategies.
- `--control-host` / `--control-port` — target a remote (tunneled) daemon; default local `control.port`.
- `--json` — machine-readable report instead of the text summary.

## The three gates — pure functions over the pulled data

A `GateEvaluator` holds pure functions `(logs, status, metrics, period, schedule) -> GateResult`. Each
returns `PASS` / `FAIL` / `REVIEW` plus structured detail.

- **Gate 1 · placement fired.** Enumerate every expected window (`scheduled hour × :55`) inside the period.
  For each, scan `/logs/<strategy>` for the OCO/bracket *submit* line in the `:55`–`:57` band. Emit a
  fired/missed table. `PASS` iff every expected window in range fired (vacuously `PASS` if the period
  contains no scheduled windows).
- **Gate 2 · no engine errors.** Scan `/logs` for `[ERROR]` lines and a curated set of bad `[WARN]`s
  (broker rejection, risk halt, reconcile anomaly) over the period. Report count + sample lines. `PASS` iff
  zero. (Known-benign WARNs — MT5 truncated-prefix orphan-attribution notes, the `volume … already exists`
  compose warning — are excluded by an allowlist.)
- **Gate 3 · PnL (REVIEW, not auto-fail).** Report cumulative `realized`/`unrealized`/`equity`/`balance`
  + `positions[]` + `lastTrade` from `/status`. Reconstruct **daily realized** by grouping per-fill
  `realized` values from `/logs` by UTC day. **Consistency check:** `Σ fills.realized ≈ /status.realized`
  (within a small tolerance). The PnL *value* never auto-fails (a legitimately red day is not a failure —
  the operator judges it against their manual expectation); only a **consistency mismatch** fails, since
  that signals a PnL-accounting bug.

## Output

A go/no-go report:
- Header: strategy, period, daemon uptime + `strategies_running` + health (from `/metrics`/`/health`).
- Per gate: `PASS`/`FAIL`/`REVIEW` with detail — gate 1 window table, gate 2 error samples, gate 3 daily-PnL
  table + cumulative + consistency line.
- Footer: overall verdict (`GO` iff gates 1 and 2 `PASS` and gate 3 consistency holds; else `REVIEW`/`NO-GO`).
- `--json` emits the same structure as JSON for scripting/cron.

## Data flow

`ControlClient` → `/metrics` (health/uptime/trades), `/status/<name>` (PnL/positions), `/logs/<name>?since`
(history) → parsed into typed snapshots → `GateEvaluator` pure functions → `ObserveReport` → text/JSON
renderer.

## File structure (proposed)

- `cli/ObserveCommand.kt` — arg parsing, orchestration, rendering. Small; delegates logic.
- `cli/observe/GateEvaluator.kt` — the three pure gate functions + `GateResult`/`ObserveReport` types.
- `cli/observe/LogScan.kt` — parse `/logs` lines into typed events (placement-submit, error/warn, fill with
  `realized`), with the timestamp + level + message extraction.
- `cli/daemon/ControlClient.kt` — extend to accept an explicit host/port.
- Tests under `src/test/kotlin/com/qkt/cli/observe/`.

## Testing

- `FakeControlClient` — an anonymous `object` returning canned `/logs`, `/status`, `/metrics` strings
  (no mocking library, per qkt conventions).
- `GateEvaluatorTest` — feed canned snapshots; assert each gate's verdict and detail (all-windows-fired vs
  a missed window; zero vs present errors; consistent vs mismatched PnL).
- `LogScanTest` — parse real log-line shapes into typed events (placement, error, fill+realized).
- `ObserveCommandTest` — end-to-end against the `FakeControlClient`, asserting the rendered verdict.

## The one daemon addition (gate 3 daily)

Daily-realized reconstruction needs per-fill `realized` visible in `/logs`. The foreground `qkt run` logs
it (`RunCommand.kt:141`: `… px=<price> realized=<realized>`); the **daemon** path
(`LiveSession.onFilled`, `LiveSession.kt:527`) has `realized` at fill time but may not log it. If it does
not, this feature adds that one log line in the daemon fill path (same shape as `qkt run`).

**Immediacy:** gates 1, 2, and cumulative PnL work against the **current v0.29.13** run immediately via the
tunnel. Only gate-3 *daily* reconstruction needs that log line, which goes live on the next deploy — the
report degrades gracefully (reports cumulative + notes daily is pending) until then.

## Scope / out of scope

- **In:** read-only observation; the three gates; text + JSON report; remote-daemon targeting; the daemon
  per-fill `realized` log line.
- **Out:** any daemon mutation; a continuous/long-running monitor or alerting (cron the one-shot if needed);
  auto-failing on PnL value; inferring the placement schedule from the strategy AST (configurable flags
  instead); multi-strategy aggregation beyond what `/status` already gives.

## Open details to settle during planning

- Exact log-line shapes for the OCO/bracket *submit* (gate 1) and the fill+`realized` line (gate 3) — to be
  read from the live code/logs and pinned in the `LogScan` parser tests.
- Whether `LiveSession.onFilled` already logs `realized` (drops the daemon addition if so).
