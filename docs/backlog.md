# qkt — backlog

Single source of truth for what's done, in progress, and queued. Update the status marker (`done`, `progress`, `tbd`) as items move.

Status legend:
- `done` — shipped and merged
- `progress` — actively in flight (spec/plan/branch in progress, or partial implementation merged)
- `tbd` — queued, not started

**Issue tracking.** Every open item is mirrored as a GitHub issue, linked at the end of its line. The working flow: find a bug or pick up an item → the issue holds the problem statement and proposed solution → solve it → close the issue. This file stays the tiered roadmap; issues are the per-item tracker. When an item ships, flip its marker to `done` here and close its issue.

---

## Done

- `done` — Phase 1: core engine MVP (tick → strategy → signal → order → broker → trade)
- `done` — Phase 2: event bus, candle aggregator, multi-strategy support, SLF4J
- `done` — Phase 3: risk engine, position tracking, P&L, BigDecimal for prices
- `done` — Phase 4: deterministic backtest replay
- `done` — Phase 5: internal Kotlin DSL, then external SQL-like parser
- `done` — Phase 6: on-disk content-addressable data store, Dukascopy auto-fetch, BYO CSV
- `done` — Phase 7c: TradingView live vendor (anonymous, free-tier) for paper trading
- `done` — Phase 7e–7h: Bybit Spot + Linear (USDT) live trading with reconciliation, rate limiting, connection resilience
- `done` — Phase 8: per-strategy P&L attribution
- `done` — Phase 9: risk engine — equity tracking, DD halts, daily loss halts, halt-as-state with operator-driven resume
- `done` — Phase 10: backtest reporting — equity curves, Sharpe, Calmar, profit factor, win/loss stats
- `done` — Phase 10b: parameter sweep harness with sequential or fixed-pool parallel execution
- `done` — Phase 10c: walk-forward analysis
- `done` — Phase 11: DSL master design and parser maturity
- `done` — Phase 11a: OSS baseline
- `done` — Phase 12a: CLI surface
- `done` — Phase 12b: observability (status/logs/metrics endpoints)
- `done` — Phase 12c: daemon (deploy/list/status/stop)
- `done` — Phase 13a: STACK pyramiding (one BUY/SELL → N price-triggered entries with optional time fence)
- `done` — Phase 13b: CANCEL/CANCEL_ALL action + PORTFOLIO file format with regime-gated child activation
- `done` — Phase 14: portfolio v2 daemon fan-out — per-child LiveSession + ports + logs + PnL; `qkt start` verb; cascade stop
- `done` — Phase 15: DSL `LOG` action
- `done` — Phase 16: backtest fidelity + HTML report
- `done` — Phase 17: MT5 broker via mt5-gateway
- `done` — Phase 18: LiveSession typed-broker dispatch
- `done` — Phase 19: pre-live confidence pack — parity test, audit-ticks, logging guide, memory leak audit
- `done` — Phase 20: top-level QUICKSTART + docker-compose stack
- `done` — Phase 21: documentation site — MkDocs Material, Mermaid, Dokka, GitHub Pages
- `done` — Phase 22: KDoc the public API — every Tier 1 type, style guide, SKILL rule for future work
- `done` — Phase 23: DSL catalog expansion — register SMA/WMA/MACD/Bollinger, add HIGHEST/LOWEST, position dot accessors, `#` comments
- `tbd`  — Phase 24: risk-sizing primitives — `SIZING N PCT RISK`, `WARMUP N BARS`, `IS NULL` / `IS NOT NULL`, `FLATTEN` DSL action ([#52](https://github.com/elitekaycy/qkt/issues/52))
- `tbd`  — Phase 25: operator tooling — `qkt fetch` CLI, `TRAILING_STOP` wiring, `per_strategy:` risk config block, VWAP DSL registration (needs TICK_SERIES input plumbing) ([#53](https://github.com/elitekaycy/qkt/issues/53))

---

## Immediate (blocks going live with real money)

- `done` — DSL `LOG` action with levels, placeholders, structured fields; stdout `[strategy]` prefix; child file slash-safe names (see [phase 15](phases/phase-15.md))
- `done` — Backtest fidelity audit: equity curve, DD-days, Monte Carlo, per-trade risk in HTML report (slippage/spread/regime deferred) (see [phase 16](phases/phase-16.md))
- `done` — Backtest vs live execution parity: identical trades (orderId/symbol/side/qty/price/timestamp) between Backtest and LiveSession given same compiled strategy and tick sequence (`src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`)
- `done` — Backtest report HTML output: single self-contained `report.html` (see [phase 16](phases/phase-16.md))
- `progress` — Tick-feed accuracy audit: `qkt audit-ticks` framework shipped (TV vs MT5 drift); operator-driven runs against live feeds (see [phase 19](phases/phase-19.md)) ([#54](https://github.com/elitekaycy/qkt/issues/54))
- `tbd` — End-to-end portfolio daemon test (deferred from Phase 14 — see `docs/phases/phase-14.md` known limitations) ([#55](https://github.com/elitekaycy/qkt/issues/55))
- `progress` — Pristine test sweep + memory leak audit: code-review audit complete, two leaks fixed (Observability executor, OrderManager risk map); two future fixes documented (CandleHub.unregister, Broker.shutdown lifecycle) (see [phase 19](phases/phase-19.md)) ([#56](https://github.com/elitekaycy/qkt/issues/56))
- `done` — Documentation MVP: MkDocs Material site with Diátaxis IA, Mermaid diagrams, Dokka API reference, GitHub Pages deploy via Actions (see [phase 21](phases/phase-21.md))

---

## Sequence-after-immediate (still pre-live, days not weeks)

- `done` — MT5 broker (multi-profile via mt5-gateway) + LiveSession typed-broker dispatch (see [phase 17](phases/phase-17.md), [phase 18](phases/phase-18.md))
- `done` — Standardized logging guide: MDC keys documented, console + file patterns specified, conventions for strategy authors + engine contributors (see [docs/logging.md](logging.md))
- `progress` — Packaging and one-shot install: full docker-compose stack (qkt + mt5-gateway) shipped (Phase 20); single-binary install pending ([#57](https://github.com/elitekaycy/qkt/issues/57))
- `progress` — CI/CD: docs build + GitHub Pages deploy shipped (Phase 21); build/test/ktlint CI green via `check.yml`; release artifact CI still pending ([#58](https://github.com/elitekaycy/qkt/issues/58))
- `tbd` — Tier 2 KDoc backfill — backtest reporting types, risk types, portfolio supervisor, indicator catalog. Phase 22 covered Tier 1; Tier 2 lands organically as features ship per the SKILL rule. ([#59](https://github.com/elitekaycy/qkt/issues/59))

---

## Performance and hardening (post-go-live)

- `tbd` — Speed and latency improvements (profiling-driven) ([#60](https://github.com/elitekaycy/qkt/issues/60))
- `tbd` — Memory and GC tuning ([#61](https://github.com/elitekaycy/qkt/issues/61))
- `tbd` — Virtual threads where applicable ([#62](https://github.com/elitekaycy/qkt/issues/62))
- `tbd` — GraalVM native image for the CLI ([#63](https://github.com/elitekaycy/qkt/issues/63))
- `tbd` — JNI for hot paths if profiles show wins ([#64](https://github.com/elitekaycy/qkt/issues/64))
- `tbd` — Stress tests, soak tests, chaos tests ([#65](https://github.com/elitekaycy/qkt/issues/65))
- `tbd` — Bundle/binary size reduction ([#66](https://github.com/elitekaycy/qkt/issues/66))

---

## Future

- `tbd` — News pipeline: Twitter, Yahoo, economic calendar, per-symbol curation; integrates into DSL ([#67](https://github.com/elitekaycy/qkt/issues/67))
- `tbd` — AI strategy-tuning pipeline: train on a strategy, derive best configs, agent layer for buy/sell decisions, continuous retraining ([#68](https://github.com/elitekaycy/qkt/issues/68))
- `tbd` — Portfolio v3: `WEIGHT` clause for capital allocation, import-time overrides, nested portfolios (carryover from Phase 14 known limitations) ([#69](https://github.com/elitekaycy/qkt/issues/69))
- `tbd` — Symbol watch dynamic extension: how strategies declare and acquire data for a totally new symbol at runtime ([#70](https://github.com/elitekaycy/qkt/issues/70))
- `tbd` — Editor tooling for `.qkt` files — parser/AST/linter, syntax highlighting, language server, VSCode + Neovim extensions. Promoted to active (not parked); decomposed and tracked as epic [#71](https://github.com/elitekaycy/qkt/issues/71) with children [#82](https://github.com/elitekaycy/qkt/issues/82)–[#86](https://github.com/elitekaycy/qkt/issues/86).
- `tbd` — Landing page ([#72](https://github.com/elitekaycy/qkt/issues/72))

---

## Post-v0.28 backlog — ranked easy → hard

Captured 2026-05-17 during the prod-readiness audit. The current focus is **getting
hedge-straddle running cleanly on Dokploy in demo, then real money**. Everything below is
either supportive of that focus or queued for after.

### Tier 0 — current focus

- `progress` — Observe hedge-straddle on prod for 1 week before flipping the broker
  login from demo to real. Items needed to call this complete:
  - 19:55 UTC verification fires correctly (Phase 30 contractSize math + v0.26.6 OCO fix
    proven under live conditions).
  - 1 week of placement windows complete without engine-side errors.
  - Daily PnL roughly matches manually-computed expectation.
  - ([#33](https://github.com/elitekaycy/qkt/issues/33))

### Tier 1 — staged work

Both items below have shipped.

- `done` — Gate Bybit market-source routes on `BYBIT_API_KEY` env presence so
  pure-MT5 deployments don't construct two idle `OkHttpClient` instances at boot.
  `MarketSourceFactory.composite` gained an `enableBybit` flag defaulting to the
  `BYBIT_API_KEY` env check. Shipped in v0.28.3 (commit `6591d7e`, merge `dd7a815`).
  ([#34](https://github.com/elitekaycy/qkt/issues/34))

- `done` — v0.28.6: `MT5StateRecovery` now correlates venue-side orphan positions back
  to the owning strategy via comment-prefix match and seeds `positionMetaByTicket` so a
  server-side close fires `OrderFilled` with the correct `strategyId`. MT5 truncates
  comments to 16 chars, so the matcher tolerates truncation (`AmbiguousTruncation` →
  seed + WARN) and rejects prefix-overlap collisions (`AmbiguousOverlap` → skip + WARN).
  Constraint: strategies sharing one MT5 magic must be prefix-disjoint.

### Tier 2 — operational / config (small, no engine code)

- `tbd` — Wire Telegram alerts in qkt-prod: bot, chat id, `TELEGRAM_ENABLED=true`. Phase
  31 infrastructure already shipped + dormant; flipping the env vars is the only step.
  ([#35](https://github.com/elitekaycy/qkt/issues/35))
- `tbd` — Docker daemon json-file log rotation for the qkt service in `qkt-prod`
  `compose.yml` (`logging.driver: json-file` with `max-size` + `max-file`). Separate from
  logback's bind-mount rotation (already done in v0.28.1).
  ([#36](https://github.com/elitekaycy/qkt/issues/36))
- `tbd` — Backup story for `qkt-prod/state/`. State persists across container restarts
  via bind mount; not backed up off-host. If the VPS disk dies mid-trade, reconciliation
  works only if the broker still has the positions. Document a backup cadence.
  ([#37](https://github.com/elitekaycy/qkt/issues/37))

### Tier 3 — Phase 31.1 (telegram alerts completion, hours)

Items I deferred when shipping Phase 31. Documented in
[`docs/phases/phase-31-telegram-alerts.md`](phases/phase-31-telegram-alerts.md) under
"Known limitations".

- `tbd` — Wire `BrokerEvent.OrderRejected` to the notifier via an `OrderManager`
  correlation map so the message can include symbol/side/quantity (currently configurable
  but silently no-ops). ([#38](https://github.com/elitekaycy/qkt/issues/38))
- `done` — Fire `NotificationEvent.DaemonStarted` from `DaemonCommand` at daemon boot.
  `NotifierFactory.fromConfig` builds the daemon notifier; the event fires when
  `daemon_started` is opted in. ([#39](https://github.com/elitekaycy/qkt/issues/39))
- `tbd` — Source a strategy-level `StrategyError` event — needs an `error` event on the
  bus that strategy adapters emit on init/load failure.
  ([#40](https://github.com/elitekaycy/qkt/issues/40))
- `tbd` — Daily-rolling tracker for `equityDeltaPct`, `tradesToday`, `haltsToday` so the
  daily summary stops rendering placeholder zeros.
  ([#41](https://github.com/elitekaycy/qkt/issues/41))
- `tbd` — Consolidate to one `DailySummaryScheduler` per daemon instead of one per
  `LiveSession`, so multi-strategy operators get one summary message at the UTC tick
  instead of N. ([#42](https://github.com/elitekaycy/qkt/issues/42))

### Tier 4 — Single-phase engine work (~1-3 days each)

Ranked by leverage for FX/commodities quant research, highest first.

- `done` — **Phase 32 — bid/ask DSL exposure**. `bid`/`ask` carried onto `Candle`,
  `<stream>.bid` / `.ask` / `.spread` exposed to the DSL and the Kotlin `StreamRef`.
  Merged `3a17386`. See [phase 32](phases/phase-32-bid-ask.md).
- `tbd` — **Phase 33 — `MT5BrokerSimulator`** for backtest fidelity. Closes the 5
  remaining backtest-vs-live divergences documented in
  [`docs/parity/backtest-vs-live.md`](parity/backtest-vs-live.md) (rows 1, 2, 3, 4-6).
  Turns "backtest result is directional only" into "backtest matches live within
  spread/slippage budget". ([#43](https://github.com/elitekaycy/qkt/issues/43))
- `tbd` — **Phase 34 — second-broker proof of life**. Add a second MT5 profile (e.g.
  ICMARKETS) to qkt-prod, deploy a trivial no-op strategy on it, observe both run on the
  same daemon. Proves multi-broker routing in production. Engine code already supports;
  this is mostly config + ops. ([#44](https://github.com/elitekaycy/qkt/issues/44))
- `tbd` — **Phase 35 — bar-level synchronized publish for paired symbols**. Two symbols
  you're spread-trading currently arrive on the bus in arbitrary order with arbitrary
  skew. A `SynchronizedCandleHub` would emit two-symbol bar events at session boundaries.
  Required for tight pairs trading. ([#45](https://github.com/elitekaycy/qkt/issues/45))
- `tbd` — **OCO restart-linkage gap**. `OrderManager.siblings` — the map that drives
  cancel-on-fill and unwind-on-reject — is populated only in `submitOco`, not rebuilt on
  daemon restart. An OCO leg rejected across a restart boundary leaves its sibling live: a
  one-legged straddle. Fix: rehydrate sibling linkage during `MT5StateRecovery`.
  ([#46](https://github.com/elitekaycy/qkt/issues/46))
- `tbd` — **`MT5Broker.submitComposite` non-atomicity**. The half-placed-OCO bug fixed in
  `submitOco` ([PR #27](https://github.com/elitekaycy/qkt/pull/27)) is still latent in
  `submitComposite`. Unreachable via the `OrderManager` OCO path today (legs dispatch
  individually), so no production impact — fix or delete when that path is next touched.
  ([#47](https://github.com/elitekaycy/qkt/issues/47))

### Tier 5 — Hedge-straddle parity gaps (Phase 36+, ~1 day each)

Strategy-side gaps documented in the strategy header. Each is a DSL/engine primitive
addition. None block deploy — they keep hedge-straddle from being bit-identical to
pa-quant.

- `tbd` — **Phase 36 — armed trailing stop** (`STOP LOSS TRAIL <distance> AFTER MFE >=
  <threshold>`). Closes hedge-straddle GAP 1. ~14% of pa-quant exits use the trail.
  ([#48](https://github.com/elitekaycy/qkt/issues/48))
- `tbd` — **Phase 37 — proportional stack sizing** (`STACK_AT SIZING <expr>` instead of
  literal). Closes GAP 2 — stacks would scale with the main leg's hour-varying size.
  ([#49](https://github.com/elitekaycy/qkt/issues/49))
- `tbd` — **Phase 38 — GTD time-in-force** for pending orders. Closes GAP 5 — the `:10`
  sweep rule workaround goes away. ([#50](https://github.com/elitekaycy/qkt/issues/50))
- `tbd` — **Phase 39 — `INSTRUMENT_META` DSL accessor**. Expose `gold.tick_size`,
  `gold.contract_size`, etc. so strategies can introspect the instrument they trade.
  Minor but useful for portable strategies. ([#51](https://github.com/elitekaycy/qkt/issues/51))

### Tier 6 — Asset-class expansion (multi-phase)

- `tbd` — **Crypto exercised end-to-end on Bybit.** Code paths exist (`BybitSpotBroker`,
  `BybitLinearBroker`, market sources). Never run in qkt-prod. First Bybit strategy will
  find latent bugs. ([#73](https://github.com/elitekaycy/qkt/issues/73))
- `tbd` — **Equity / stocks broker adapter.** No equity broker exists. Needs new broker
  like `MT5Broker` (Interactive Brokers / Alpaca / TradeStation) plus stock-specific
  concepts: corporate actions, dividend adjustment, pre/post-market sessions, halts.
  Substantial — multi-phase work. ([#74](https://github.com/elitekaycy/qkt/issues/74))
- `tbd` — **Futures broker adapter** (e.g. Tradovate, IBKR futures). Similar scope to
  equities — distinct order types, session calendars, expiry roll logic.
  ([#75](https://github.com/elitekaycy/qkt/issues/75))

### Tier 7 — DSL surface expansion (as needed)

The DSL has 28+ phases of primitives but most are exercised only by hedge-straddle. The
following are likely to surface as the second/third strategy gets written:

- `tbd` — Cross-strategy state sharing (one strategy reads another's positions or
  equity). Currently strategies are isolated via `StrategyPositionTracker` namespacing;
  cross-reads would need an explicit query primitive.
  ([#76](https://github.com/elitekaycy/qkt/issues/76))
- `tbd` — Symbol watch dynamic extension (tracked in the **Future** section above,
  [#70](https://github.com/elitekaycy/qkt/issues/70)).
- `tbd` — DSL action: `SCHEDULE` for time-of-day non-tick-driven actions (currently
  done via `WHEN NOW.minute_utc = N`, which works but is awkward).
  ([#77](https://github.com/elitekaycy/qkt/issues/77))

### Tier 8 — Platform maturity (post-research-platform)

- `tbd` — Inbound Telegram bot commands (`/status`, `/halt`, `/resume` from phone) —
  needs long-polling or webhook receiver. ([#78](https://github.com/elitekaycy/qkt/issues/78))
- `tbd` — HTTP `/metrics` endpoint (Prometheus-compatible) for `NotifierMetrics` +
  engine internals. ([#79](https://github.com/elitekaycy/qkt/issues/79))
- `tbd` — Multi-host deployment (active-passive failover for the qkt daemon).
  ([#80](https://github.com/elitekaycy/qkt/issues/80))
- `tbd` — `qkt research` REPL — interactive strategy authoring with live tick replay
  against historical data. ([#81](https://github.com/elitekaycy/qkt/issues/81))

---

## How to maintain this file

- Update the status marker in place (`done` ↔ `progress` ↔ `tbd`).
- Every open item carries a GitHub issue link. When you open a new item, create its issue
  (problem statement + proposed solution) and link it here. When an item ships, flip it to
  `done` and close the issue.
- When an item ships, leave it in its current section but flip to `done`. Move to the **Done** section only at the next major housekeeping pass.
- New items go into the section that matches their priority. If unsure between immediate and future, default to future.
- Keep entries one line each. If an item needs more detail, link to its spec/plan/changelog.
