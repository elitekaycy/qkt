# Bot Run Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** External programs make trading decisions through `qkt bot` verbs against a
session daemon that provides real risk (qkt.config.yaml), full backtest reports, and
insights run grouping — identical client loop in backtest and live.

**Architecture:** A `BotRunSession` wraps the existing replay/live pipeline. In backtest
it holds a `ReplayEngine` built by the existing `Backtest.fromStore` path, paced by
`advanceUntil`; bars are recorded and counted by a `BotSessionRecorder` strategy (a plain
`Strategy` whose `onCandle` feeds a `BarHistory` ring buffer), and external intents enter
via `BotBridgeStrategy` instances (one per declared identity) that drain a queue into
`emit(Signal)`. No ReplayEngine changes. A small HTTP server (same
`com.sun.net.httpserver` + bearer-token pattern as `ControlPlane`) exposes the verbs;
one-shot `qkt bot` commands route to it when a session resolves, else behave exactly as
today.

**Tech Stack:** Kotlin, JUnit5+AssertJ (real types, no mocks), existing
`Backtest`/`ReplayEngine`/`BacktestReportWriter`/`BotIntent`/`BotTrail`/`Config`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-19-bot-run-sessions-design.md`.
- No behavior change to any existing path: no-session bot verbs byte-identical;
  `ReplayEngine`, `Backtest`, `BacktestReportWriter` production code untouched
  (new callers only).
- ktlint-clean per cycle; files ≤ ~150 lines where feasible; KDoc on all public types.
- Commits: `<type>(<scope>): <subject>`, no body, no AI attribution.
- Branch: `phase-bot-sessions` off `dev` (feature branch; spec branch merges first or
  is folded in).
- Money BigDecimal; timestamps `Long` `...Ms`; null = cannot-compute; fail-closed.

---

### Task 1: BarHistory ring buffer

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/BarHistory.kt`
- Test: `src/test/kotlin/com/qkt/trade/session/BarHistoryTest.kt`

**Interfaces:**
- Produces: `class BarHistory(private val capacity: Int)` with
  `fun record(candle: Candle)`, `fun last(symbol: String, count: Int): List<Candle>`
  (newest-last, ≤ count, fewer if fewer seen — caller decides fail-closed),
  `fun countFor(symbol: String): Long` (monotonic total closed bars seen),
  `fun seed(symbol: String, bars: List<Candle>)` (warmup pre-load, counts as recorded).
  Keyed by candle symbol only (one session = one timeframe, v1 limitation per spec).

- [ ] Write failing test: records N>capacity bars, `last` returns newest `capacity`;
      `countFor` counts all; `seed` then `record` interleave correctly; unknown symbol
      → empty list / 0.
- [ ] Implement with `ArrayDeque<Candle>` per symbol + `Long` counters. O(1) record.
- [ ] `./gradlew test --tests '*BarHistoryTest*'` PASS; ktlintFormat+Check.
- [ ] Commit `feat(strategy): add session bar history buffer` (scope `strategy` if
      placed under strategy package; use actual package's scope — `trade` is not a
      valid scope, so place under `com.qkt.strategy.session` OR commit as
      `feat: ...` scopeless. Decision: package `com.qkt.trade.session`, commit
      scopeless `feat: add bot session bar history buffer`.)

### Task 2: BotBridgeStrategy + BotSessionRecorder

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/BotBridgeStrategy.kt`
- Create: `src/main/kotlin/com/qkt/trade/session/BotSessionRecorder.kt`
- Test: `src/test/kotlin/com/qkt/trade/session/BotBridgeStrategyTest.kt`

**Interfaces:**
- `class BotBridgeStrategy : Strategy` — `fun submit(signal: Signal)` enqueues
  (`java.util.concurrent.ConcurrentLinkedQueue<Signal>`); `onTick` drains all queued
  signals into `emit` (with `isEmpty()` early-return); `onCandle` no-op.
- `class BotSessionRecorder(private val history: BarHistory) : Strategy` — `onTick`
  no-op; `onCandle` calls `history.record(candle)`.

- [ ] Failing test: enqueue Buy+Submit, drive `onTick` with a real `Tick` and capture
      list → both emitted in order, queue empty after; second tick emits nothing.
      Recorder test: onCandle → history.last returns the candle.
- [ ] Implement; run tests PASS; ktlint clean.
- [ ] Commit `feat: add bot bridge strategy and session recorder`.

### Task 3: BotRunSession (backtest core)

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/BotRunSession.kt`
- Create: `src/main/kotlin/com/qkt/trade/session/BotRunConfig.kt` (run id, identities,
  symbols, tf, window, out dir, starting balance, history capacity)
- Test: `src/test/kotlin/com/qkt/trade/session/BotRunSessionTest.kt`

**Interfaces (consumed by Tasks 4–6):**
- `class BotRunSession(cfg: BotRunConfig, engine: ReplayEngine, bridges: Map<String,
  BotBridgeStrategy>, history: BarHistory, readsJournal: Path?)` with:
  - `fun next(symbol: String): Candle?` — `engine.advanceUntil {
    history.countFor(symbol) > before || engine.exhausted }`; returns newest bar or
    null when exhausted. Records a read line.
  - `fun bars(symbol: String, count: Int): List<Candle>` (from history, ≤ sim-now)
  - `fun quote(symbol: String): Tick-ish` — from engine price tracker via a
    last-tick cache fed by the recorder (add `lastTick` to recorder onTick)
  - `fun submit(identity: String, signal: Signal): SubmitAck` — rejects undeclared
    identity fail-closed; enqueues on that bridge (executes on next advance)
  - `fun positions()/equity()/status()` — engine reads
  - `fun finish(): BacktestResult` — `engine.advanceToEnd()` optional? NO: finish
    stops at sim-now (drain only current bar) then `engine.snapshot()`; caller
    (CLI Task 5) writes report via `BacktestReportWriter`.
- Companion `fun forBacktest(cfg, store paths...): BotRunSession` — builds
  `Backtest.fromStore(strategies = identities.map { it to BotBridgeStrategy() } +
  ("__recorder" to BotSessionRecorder(history)), rules/halts from Config the same way
  BacktestContext derives them, request/window/tf, brokerKind from --broker
  default PAPER).toEngine()`. Warmup: seed history via
  `source.bars(sym, window, TimeRange(from - warmupSpan, from))` before first next().
  Note: `__recorder` must be excluded from report perStrategy — verify snapshot
  includes it (it has no trades; acceptable — it appears as an all-zero row) →
  decision: filter `__recorder` rows in the CLI report step, not in engine.

- [ ] Failing test (real ticks fixture, no mocks): 3-bar synthetic tick list,
      session.next twice returns 2 bars in order; submit Buy between next calls →
      trade appears in `finish().trades` with the identity's strategyId; bars(count)
      never returns a bar newer than last next(); undeclared identity rejected.
- [ ] Implement; PASS; ktlint.
- [ ] Commit `feat: add bot run session over paced replay`.

### Task 4: Session HTTP server + session files

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/BotSessionServer.kt`
- Create: `src/main/kotlin/com/qkt/trade/session/BotSessionFiles.kt` (session dir under
  `<stateRoot>/bot/sessions/<run>/`: `session.json` {run, port, token, mode, configPath,
  identities, pid}, `reads.jsonl`, report out dir)
- Test: `src/test/kotlin/com/qkt/trade/session/BotSessionServerTest.kt`

**Interfaces:**
- `BotSessionServer(session: BotRunSession, token: String, bind=127.0.0.1, port=0)`
  routes (all bearer-token, JSON):
  `POST /next {symbol}`, `GET /bars?symbol&count`, `GET /quote?symbol`,
  `GET /positions`, `GET /account`, `POST /intent {identity, canonicalDsl|fields...}`,
  `GET /status`, `POST /finish`. Single-threaded executor for engine-touching routes
  (the engine is single-threaded; serialize access).
- Intent transport: reuse the existing bot argv→`BotIntent`→`renderBotStrategy`→
  `parseBotStrategy` on the CLIENT side; POST the canonical DSL source + identity;
  server re-parses (`parseBotStrategy`) and compiles to a `Signal`:
  market → `Signal.Buy/Sell`; limit/stop/bracket → build `OrderRequest` via a new
  `fun BotAction.toSignal(id, ts, identity): Signal` in
  `src/main/kotlin/com/qkt/trade/session/BotActionSignal.kt` mirroring
  `compileBotAction` shapes but emitting pipeline orders (sizing DSL like
  `1 PCT RISK` compiles through the normal pipeline sizing on the engine side —
  v1 supports lots + SL/TP at/by/pct/rr resolved against last quote from history,
  same helpers `compileBotAction` uses; engine-managed shapes stay rejected).
- [ ] Failing test: start server on port 0, hit /status and /next with real session
      from Task 3 fixture; auth: wrong token → 401.
- [ ] Implement; PASS; ktlint. Commit `feat: add bot session http server`.

### Task 5: CLI — session verbs, `bot next`, and session routing

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/bot/BotSessionCommand.kt` (`session start`
  backtest mode v1: builds store/source exactly as `BacktestContext` does for
  `--from/--to/--symbols/--tf/--bars/--tick-fills/--broker`, constructs
  `BotRunSession.forBacktest`, starts server, writes session.json, RUNS IN
  FOREGROUND unless `--detach` (ProcessBuilder re-exec, daemon pattern);
  `session status|finish` via HTTP; finish filters `__recorder` and writes
  `BacktestReportWriter(outDir).write(result)`)
- Create: `src/main/kotlin/com/qkt/cli/bot/BotSessionClient.kt` (resolve session:
  `--run` → `QKT_BOT_RUN` → newest `session.json` under state root; HTTP calls)
- Modify: `src/main/kotlin/com/qkt/cli/bot/BotCommand.kt` (add verbs `session`,
  `next`; route `buy/sell/quote/bars/positions/account` through
  `BotSessionClient` when a session resolves — same output shapes; else unchanged)
- Test: `src/test/kotlin/com/qkt/cli/bot/BotSessionCliTest.kt`

- [ ] Failing test: end-to-end in-process — session start (foreground thread) on a
      tick fixture dir, `bot next`/`bot buy --run` via client → finish writes
      trades.csv/result.json; without session, `bot quote` still errors exactly as
      today (no gateway) — regression guard.
- [ ] Implement; PASS; ktlint. Commit `feat(app): route bot verbs through run sessions`
      (scope: use `app`? bot cli lives under `cli` — allowed scopes lack `cli`; use
      scopeless `feat: ...`).

### Task 6: Live mode

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/BotLiveFeed.kt`
- Modify: `BotSessionCommand.kt` (no `--backtest` → live wiring)
- Test: `src/test/kotlin/com/qkt/trade/session/BotLiveFeedTest.kt` (feed unit only;
  venue integration is scripted, Task 8)

**Decision gate (read `LiveSession.kt` first):** primary path = reuse `LiveSession`
with bridge strategies if its constructor accepts hand-written `Strategy` instances;
fallback = `BotRunSession` over a `BotLiveFeed : TickFeed` (blocking queue fed by a
gateway tick poller thread, `feed.next()` blocks) with `brokerKind` mt5 via existing
BrokerFactory if injectable, else declared-divergence thin path: risk-checked intents
placed via `BotGateway` with halt/exposure state from engine trackers. Whichever path
lands, record it in the spec's parity section in the same commit.

- [ ] Read LiveSession construction; pick path; implement + unit-test the feed.
- [ ] Commit `feat: add live mode for bot run sessions`.

### Task 7: Insights run tagging + reads journal

**Files:**
- Modify: `src/main/kotlin/com/qkt/trade/BotTrail.kt` (optional `run: String?`
  ctor param, default null → payload `"run" to run` when non-null; existing callers
  unchanged via default)
- Modify: `BotRunSession.kt` (reads.jsonl line per next/bars/quote:
  `{tsMs, simMs, verb, symbol, count?, identity?}`; BotTrail wiring for session
  submits with run id)
- Test: extend `BotRunSessionTest` (reads.jsonl written) +
  `src/test/kotlin/com/qkt/trade/BotTrailTest.kt` if absent (envelope payload
  carries run)

- [ ] Failing tests; implement; PASS; ktlint.
- [ ] Commit `feat: tag bot session insights and journal reads`.

### Task 8: Verification campaign (no new prod code)

- [ ] `./gradlew build` green (full compile+tests) — regression proof.
- [ ] Parity test (add `src/test/kotlin/com/qkt/trade/session/BotSessionParityTest.kt`):
      same tick fixture + same decision timeline run (a) through BotRunSession with a
      scripted client loop and (b) through plain `Backtest` with a scripted `Strategy`
      emitting the identical signals on the same bars → assert equal trade tapes and
      final equity (`isEqualByComparingTo`). This is the test the spec's parity claim
      cites.
- [ ] Live smoke script `tests/scripts/bot-session-live-smoke.sh`: uses the local
      Exness `mt5-gateway` container (`docker ps`), starts a live session, `bot next`,
      `bot buy` 0.01, `bot positions`, `bot close`, `session finish`; asserts JSON oks.
      Run it; capture output.
- [ ] Backtest e2e script `tests/scripts/bot-session-backtest-smoke.sh` against cached
      data (bot2-style local store or committed fixture); verify report dir contents
      match `qkt backtest` artifact list.
- [ ] Docker check: run session inside `qkt` image (`docker run ... qkt bot session
      start --backtest ...`) — confirm state-dir/port behavior in-container; document
      required `-p`/volumes in template README (Task 9).
- [ ] Commit `test: bot session parity and smoke scripts`.

### Task 9: `qkt create --kind bot` template prod-ready

**Files:**
- Modify: `src/main/resources/templates/bot/MANIFEST`, `BOT.md.tmpl`,
  `SYSTEM_PROMPT.md.tmpl`
- Create: `src/main/resources/templates/bot/README.md.tmpl` (everything you can do:
  one-shot verbs, session backtest walkthrough, session live walkthrough, python
  executor example, report artifacts, risk config wiring, insights)
- Create: `src/main/resources/templates/bot/docker-compose.override.yml.tmpl` or edit
  the layered mt5 compose usage docs — include a **commented-out qkt-insights
  service block** ready to enable.
- Test: extend existing scaffolder test (find `TemplateScaffolder` tests) to assert
  new files land for `--kind bot`.

- [ ] Implement, scaffold to a temp dir, eyeball README, test green, ktlint n/a.
- [ ] Commit `feat: prod-ready bot template with session workflow readme`.

### Task 10: Docs + PR + promotion

- [ ] Update spec parity/divergence notes with the Task 6 decision; add docs page
      `docs/` (mkdocs) for bot sessions if a bot docs page exists — extend it.
- [ ] Pre-push checklist (§4 of qkt skill): build, test, status clean, commit-log
      review, TODO grep.
- [ ] PR feature branch → `dev` with the template description; merge --no-ff after CI.
- [ ] Confirm `dev` essentials CI green → fast-forward promotion to `testing`
      (automatic; verify it happened).

## Self-review

- Spec coverage: run context (T3/T5), data shapes (T3/T4), next/stream (T3/T5 — v1
  ships `next` only; `stream` deferred, spec updated in T10 — spec lists stream as
  push alternative; note as known limitation), orders through pipeline (T2/T4), warmup
  (T3 seed), identities/report rows (T3/T8), insights (T7), stateless fallback
  untouched + `--enforce-risk` (deferred — NOT in v1; spec open-questions updated in
  T10), reports (T5), parity (T8), template (T9).
- Type consistency: `BarHistory.countFor` used by T3 `next`; `BotBridgeStrategy.submit`
  used by T4 `/intent`; `BotRunSession.finish(): BacktestResult` consumed by T5.
