# Bot run sessions — external decision sources through the bot CLI

Date: 2026-08-19
Status: Draft for review

## Summary

Let any external program (Python model, LLM agent, human script) make trading
decisions through the `qkt bot` CLI while getting the full power of qkt: risk
rules from `qkt.config.yaml`, MT5 symbol handling, insights attribution, and —
critically — true backtesting of the external logic over cached historical
data with the exact same report artifacts as `qkt backtest` (trades.csv,
equity, pnl_components, result.json, report.html).

The core primitive is a **run session**: a background daemon holding one
`TradingPipeline` (feed, risk engine, OMS, broker). The external program stays
in control — it pulls data and pushes orders via plain `qkt bot` verbs, which
transparently route to the session. Backtest and live differ only in what sits
behind the session (replay feed + PaperBroker + sim clock vs. gateway feed +
MT5 + real clock), which is the existing TradingPipeline parity contract.

## Goals

- External decision source pulls market data and pushes orders through
  `qkt bot` verbs, same JSON shapes in backtest and live.
- Orders pass through the real risk engine configured by `qkt.config.yaml`
  (halts, exposure caps, per-strategy limits), not straight to the venue.
- A backtest run over a chosen data window produces the full
  `BacktestReportWriter` artifact set, byte-same-shape as `qkt backtest`.
- Everything in a run — reads, orders, fills — is grouped under one run
  identity that qkt-insights can reference; multiple external strategies
  under one run are distinguished by `--as` name (external "portfolio").
- The existing direct-to-venue bot path is untouched when no session exists.

## Non-goals

- Engine-driven callback protocol (qkt calling out to a decider process).
  The external program owns its loop; qkt is a service. Recorded as a
  possible later addition for backtesting fast deciders over long windows.
- Bit-identical replay of a nondeterministic external agent. Determinism of
  the *pipeline* is preserved; determinism of the agent is the agent's
  property (declared divergence, see Parity).
- Multi-account sessions. One session = one account = one config. Insights
  already groups across accounts.
- Engine-managed order shapes via bot verbs (trailing, STACK, OCO, ON FILL).
  The existing fail-closed rejection in `BotActionCompiler` stands; those
  shapes require a deployed `.qkt`.

## Design

### 1. Run session lifecycle

```bash
# backtest
qkt bot session start --backtest \
  --symbols ICM:XAUUSD --from 2025-01-01 --to 2025-06-30 \
  --bars 5m --tick-fills --config qkt.config.yaml \
  --identities momentum-v2,meanrev-v1 --out runs/brain1
# -> prints run id (default: derived from --out basename, override --run)

# live: same command without --backtest/--from/--to/--out
qkt bot session start --symbols ICM:XAUUSD --config qkt.config.yaml

qkt bot session status --run brain1
qkt bot session finish --run brain1     # backtest: writes report; live: graceful stop
```

The session daemon assembles the same `TradingPipeline` that `Backtest.kt` /
`LiveSession` build today: feed → bus → candle aggregation → risk (from
`Config.load` of `qkt.config.yaml`; the risk maps are independent of any
deployed `.qkt`, and the mandatory `PreTradeControls`/`RunawayBreaker` floor
applies even with zero DSL strategies) → OMS → broker (`PaperBroker` when
`--backtest`, MT5 otherwise).

The strategy slot holds one `BotBridgeStrategy` **per declared identity**
(see §6). Each is a plain `Strategy`: a thread-safe intent queue drained into
`emit(Signal)` on the next `onTick` callback — the same inbound-command
pattern `LiveSession` already uses for `Flatten`/`Query` (engine-thread queue,
`LiveSession.kt` `Inbound`).

Control plane: reuse the existing `ControlPlane` HTTP server + bearer token
(`ControlRoutes.kt`) with new routes under `/bot/*`:
`POST /bot/intent`, `GET /bot/bars|quote|positions|orders|account`,
`POST /bot/next` (blocking pull, §3), `GET /bot/stream` (chunked JSONL),
`POST /bot/finish`. Session metadata (port, token, run id, config path) is
written to `<stateRoot>/bot/sessions/<run-id>/session.json`.

### 2. Data sourcing and shapes

- **Backtest:** session start resolves `--symbols/--from/--to` against the
  same local historical store `qkt backtest` uses (binary tick cache,
  `LocalMarketSource` symbol bridging, `--bars`/`--tick-fills` tiering).
  Missing data fails closed with a `datafetch` instruction. Assumes data
  already exists in qkt backtest format; no new data machinery.
- **Live:** the gateway feed poller and `CandleAggregator`, as `LiveSession`
  wires them today.

One serializer, one schema, both modes:

```json
{"type":"bar","broker":"ICM","symbol":"XAUUSD","tf":"5m",
 "timeMs":1755590400000,"open":2410.1,"high":2412.4,"low":2409.8,
 "close":2411.9,"volume":1523}
```

`bot quote` returns `{bid, ask, timeMs}` at sim-now (backtest) or venue-now
(live). `bot bars --count N` returns an array of bar objects.

**New component — `BarHistory`** (the one genuinely new piece of pipeline
state, audit verdict HARD): a bounded ring buffer per (symbol, tf) that
subscribes to `CandleEvent` and retains the last N closed bars. Today
`CandleAggregator` discards closed candles after emit, and `bot bars` fetches
from the venue over HTTP — useless against a sim clock. `BarHistory` is
seeded during warmup (§5) and serves all in-session `bot bars` queries in
both modes, which also makes live `bot bars` consistent with what strategies
saw rather than a separate venue fetch. Cold-path component; per-bar O(1)
append, capacity = max(warmup need, configured `--history-bars`, default
1000).

**Anti-lookahead guarantee:** in backtest, reads are answered from
`BarHistory`/tracker state at the replay cursor. The store is never queried
past sim-now. Pre-window warmup history is served (that is warmup, not
lookahead).

### 3. Clock and the pull loop

The external program owns the loop. The blocking-pull primitive:

```bash
qkt bot next ICM:XAUUSD --tf 5m --run brain1 --json
```

- **Live:** blocks until the next bar for that stream closes, returns it.
- **Backtest:** advances replay until the next bar close for that stream,
  returns it instantly. Implemented on the existing step machinery —
  `ReplayEngine.advanceUntil(stop)` and `ReplaySession.StepBars` already
  exist (audit verdict EASY); the addition is a per-stream bar counter
  (today `barsClosed` tracks only the primary candle window). When the
  window is exhausted, returns `{"type":"end"}`.

`bot stream --run <id> --bars 5m` is the push alternative: chunked JSONL,
backpressure-paced in backtest (next event emitted when the client has
consumed the previous one). `next` and `stream` are two views of the same
cursor; a run uses one or the other per stream.

Ticks between the consumed bar closes are replayed internally, so fills,
triggers, SL/TP, and risk all evaluate at full fidelity (`--tick-fills`
semantics unchanged).

Worked loop (identical for backtest and live):

```python
bars = qkt("bars", "ICM:XAUUSD", "--tf", "5m", "--count", "200")  # prime
while (bar := qkt("next", "ICM:XAUUSD", "--tf", "5m"))["type"] != "end":
    if model.decide(bar) == "buy":
        qkt("buy", "ICM:XAUUSD", "--sizing", "1 PCT RISK",
            "--sl", "by:30", "--tp", "rr:2", "--as", "momentum-v2")
```

### 4. Orders through the pipeline

`bot buy|sell|close|modify|cancel` with a resolved session POST an intent to
`/bot/intent`. The session parses/validates it with the existing
`BotIntent → renderBotStrategy → parseBotStrategy → BotAction` path (same
canonical-DSL validation and sha), then enqueues it on the target identity's
`BotBridgeStrategy`. On the next engine event it is emitted as a `Signal`
(`Signal.Submit(OrderRequest)` covers limit/stop/bracket; `Buy`/`Sell` the
market cases) and flows through sizing, risk, OMS, broker exactly as a
deployed strategy's signal would. Risk rejections return in the command's
JSON result and land in `rejections.csv` (backtest).

Intents are timestamped at sim-now = the last event the submitting stream
consumed. An intent arriving between `next` calls executes on the first
replayed tick after submission.

### 5. Warmup

- **Session warmup (backtest):** `ReplayEngine`'s existing pre-roll
  (`PerStreamWarmupCoordinator`/`IndicatorWarmer`) keys off DSL strategies'
  declared streams — a bot session has none (audit gap). New seam: session
  start declares its streams explicitly (from `--symbols`/`--tf`/
  `--history-bars`) and calls `WarmupHistoryLoader.loadAvailable` to seed
  `BarHistory` and candle hubs before parking at `--from`. Risk trackers
  start clean at `--from`.
- **Session warmup (live):** gateway warmup fetch as `LiveSession` does;
  `bot bars`/`bot next` before warmup completes fail closed with
  `"warming up"`.
- **Agent warmup:** the agent primes itself via `bot bars --count N`. If N
  exceeds available history, the call fails closed rather than serving a
  silently short series.

### 6. Identity, insights, and external portfolio

- **Run id** is the spine. Every insights envelope the session emits carries
  it in the **payload** (`"run": "brain1"`), keeping the
  `InsightsEnvelope` schema untouched (audit: payload convention = easy;
  promoting run to a first-class envelope field is a deferred cross-repo
  change with qkt-insights).
- **Per-order identity** rides the existing `strategyId` envelope field via
  `--as`, exactly as `BotTrail` does today. Insights therefore groups:
  run → strategies → orders/fills, the same shape as the portfolio-prefix
  dslName convention it already renders.
- **Venue attribution:** order id is `bot-<run>-<as>-<seq>`; the full id
  travels in `client_order_id` (untruncated, authoritative) and the MT5
  comment carries its lossy 29-char prefix, as today. Recovery matches by
  prefix; `<run>-<as>` early in the id keeps prefixes discriminating.
- **Identities are declared at session start** (`--identities a,b,c`;
  default single identity `manual`). Each becomes an entry in the pipeline's
  `strategies` list, so `ReplayEngine.snapshot()` produces per-identity
  `PerformanceReport`s with zero new report machinery (audit verdict EASY —
  no PORTFOLIO machinery needed). An order with an undeclared `--as` is
  rejected fail-closed with the declared list in the message. Rationale:
  the strategies list is fixed at pipeline construction; lazy identity
  creation would mutate engine wiring mid-run.
- Account-level risk rules see the whole session; per-strategy risk limits
  from `qkt.config.yaml` (`perStrategyRisk`) apply per identity.

### 7. Session routing and the stateless fallback

Resolution order for every bot verb: `--run <id>` flag → `QKT_BOT_RUN` env →
`session.json` discovered under the state root for the config resolved from
cwd. If a session resolves, the verb is a thin HTTP client of it. If none
resolves, behavior is exactly today's direct path (`BotGateway` straight to
the venue) — byte-identical, since `--config` already exists for gateway
resolution and must not change meaning. Stateless risk checking is opt-in
via a new explicit `--enforce-risk` flag: the one-shot then evaluates the
config's point-in-time-checkable risk rules against venue truth (positions +
today's deals) before placing, and reports which rules were checked and
which are session-only. Stateless mode
is live-only and cannot backtest (nothing holds a replay cursor); the CLI
says so if `--backtest`-ish flags appear without a session.

The injection seam is the existing centralized `gateway()`/`botConfig()`
resolution in `BotCommand`/`BotTradeCommand`/`BotQueryCommand` (audit:
single obvious seam; the transport is the net-new part, and it reuses
`ControlClient`).

### 8. Reports

`qkt bot session finish` on a backtest run: drain/settle per config, then
`BacktestReportWriter.write(result)` into `--out` — result.json, trades.csv,
equity_global.csv + `equity_<identity>.csv`, pnl_components.csv,
rejections.csv, orders.jsonl, report.html, manifest.json. Identical writer,
identical shapes to `qkt backtest`.

Additions:
- `reads.jsonl` — every quote/bars/next/stream delivery with sim-timestamp
  and identity, so the report captures what the agent *saw*.
- `manifest.json` gains a `run` block: run id, identities, config sha,
  data window, fill tier.

Live runs have no report writer; insights is the live report, grouped by
the same run/identity tags.

### 9. Parity

Backtest and live share the pipeline; the session differs only in feed,
clock, and broker — the existing contract. The external client script is
byte-identical between modes. Declared divergences (to be added to
`docs/parity/backtest-vs-live.md` in the implementing PR):

1. Agent nondeterminism: replaying the same window with a nondeterministic
   agent yields different decisions. Pipeline determinism is unaffected;
   given the same decision sequence, results are deterministic.
2. Intent timing: live intents land at wall-clock arrival; backtest intents
   land at sim-now of the submitting stream's cursor. Sub-bar timing skew
   between modes is bounded by one bar for bar-paced agents.

### 10. Hot/cold classification

Cold: session lifecycle, verb routing, HTTP handling, report writing,
reads journal. Hot-path additions: `BarHistory` append (O(1) per bar close,
per stream) and the `BotBridgeStrategy` queue drain (O(pending intents) per
tick with an `isEmpty()` early-return, normally zero). Both follow §9 of the
qkt skill (no per-tick allocation on the empty path).

## Feasibility (audited 2026-08-19)

| Piece | Verdict | Basis |
|---|---|---|
| Paced replay / `bot next` | EASY | `ReplayEngine.advanceUntil` + `ReplaySession.StepBars` exist; add per-stream bar counter |
| Intent → Signal bridge | EASY–MODERATE | `Strategy` callback contract + `LiveSession` `Inbound` queue precedent |
| Session control plane | EASY | `ControlPlane`/`ControlRoutes`/`ControlClient` mature; add `/bot/*` routes |
| Per-identity reports | EASY | `BacktestResult.perStrategy` keyed by strategies list; no PORTFOLIO machinery |
| Risk config reuse | EASY | `Config.load` risk maps independent of deployed `.qkt`; mandatory floor applies |
| Insights run tagging | EASY (payload) | `strategyId` carries `--as`; run in payload; first-class field deferred |
| Venue attribution | EASY | `client_order_id` untruncated; comment 29-char lossy prefix as today |
| Verb session routing | MODERATE | Single resolution seam; HTTP client transport net-new |
| Session warmup sans DSL | MODERATE | `WarmupHistoryLoader.loadAvailable` exists; new declaration seam |
| `BarHistory` | HARD (bounded) | Genuinely new pipeline state; small, cold-adjacent, O(1) |

## Testing

- `BotBridgeStrategy`: intents enqueued → emitted as Signals on next event,
  in order, timestamped at sim-now; empty queue adds no per-tick work.
- `BarHistory`: retains exactly N closed bars per stream; serves count ≤ N;
  fails closed beyond available history; seeded correctly from warmup.
- End-to-end backtest session (real cached fixture data, no mocks): start →
  prime → scripted decision loop over `bot next` → orders (accepted and
  risk-rejected) → finish; assert report artifacts match a hand-assembled
  `Backtest` run of an equivalent scripted `Strategy` — this is the parity
  test the claims cite.
- Anti-lookahead: `bot bars` at cursor T never returns a bar closing > T.
- Multi-identity: two identities' trades split into per-identity equity CSVs
  and report rows.
- Stateless fallback: no session → today's direct-path behavior byte-
  unchanged (regression on existing bot tests).

## Open questions

- Promote run id to a first-class `InsightsEnvelope` field (cross-repo with
  qkt-insights) — deferred until payload convention proves limiting.
- Engine-driven callback lane for backtesting fast deciders over multi-year
  windows (stream-paced replay runs at agent speed) — deferred.
- `bot next` multiplexing for agents consuming several streams from one
  process (currently one blocking call per stream; a `--any` form could
  return whichever stream closes next).

## References

- Feasibility audits: this spec's table (ReplayEngine.kt, LiveSession.kt,
  ControlRoutes.kt, BotTrail.kt, InsightsEnvelope.kt, MT5WireTypes.kt,
  CandleAggregator.kt, BacktestReportWriter.kt, Config.kt citations).
- Existing bot surface: `cli/bot/*`, `trade/Bot*`.
- Parity contract: `TradingPipeline.kt`, `docs/parity/backtest-vs-live.md`.
