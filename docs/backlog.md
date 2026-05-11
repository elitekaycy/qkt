# qkt — backlog

Single source of truth for what's done, in progress, and queued. Update the status marker (`done`, `progress`, `tbd`) as items move.

Status legend:
- `done` — shipped and merged
- `progress` — actively in flight (spec/plan/branch in progress, or partial implementation merged)
- `tbd` — queued, not started

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
- `tbd`  — Phase 24: risk-sizing primitives — `SIZING N PCT RISK`, `WARMUP N BARS`, `IS NULL` / `IS NOT NULL`, `FLATTEN` DSL action
- `tbd`  — Phase 25: operator tooling — `qkt fetch` CLI, `TRAILING_STOP` wiring, `per_strategy:` risk config block, VWAP DSL registration (needs TICK_SERIES input plumbing)

---

## Immediate (blocks going live with real money)

- `done` — DSL `LOG` action with levels, placeholders, structured fields; stdout `[strategy]` prefix; child file slash-safe names (see [phase 15](phases/phase-15.md))
- `done` — Backtest fidelity audit: equity curve, DD-days, Monte Carlo, per-trade risk in HTML report (slippage/spread/regime deferred) (see [phase 16](phases/phase-16.md))
- `done` — Backtest vs live execution parity: identical trades (orderId/symbol/side/qty/price/timestamp) between Backtest and LiveSession given same compiled strategy and tick sequence (`src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`)
- `done` — Backtest report HTML output: single self-contained `report.html` (see [phase 16](phases/phase-16.md))
- `progress` — Tick-feed accuracy audit: `qkt audit-ticks` framework shipped (TV vs MT5 drift); operator-driven runs against live feeds (see [phase 19](phases/phase-19.md))
- `tbd` — End-to-end portfolio daemon test (deferred from Phase 14 — see `docs/phases/phase-14.md` known limitations)
- `progress` — Pristine test sweep + memory leak audit: code-review audit complete, two leaks fixed (Observability executor, OrderManager risk map); two future fixes documented (CandleHub.unregister, Broker.shutdown lifecycle) (see [phase 19](phases/phase-19.md))
- `done` — Documentation MVP: MkDocs Material site with Diátaxis IA, Mermaid diagrams, Dokka API reference, GitHub Pages deploy via Actions (see [phase 21](phases/phase-21.md))

---

## Sequence-after-immediate (still pre-live, days not weeks)

- `done` — MT5 broker (multi-profile via mt5-gateway) + LiveSession typed-broker dispatch (see [phase 17](phases/phase-17.md), [phase 18](phases/phase-18.md))
- `done` — Standardized logging guide: MDC keys documented, console + file patterns specified, conventions for strategy authors + engine contributors (see [docs/logging.md](logging.md))
- `progress` — Packaging and one-shot install: full docker-compose stack (qkt + mt5-gateway) shipped (Phase 20); single-binary install pending
- `progress` — CI/CD: docs build + GitHub Pages deploy shipped (Phase 21); build/test/ktlint CI green via `check.yml`; release artifact CI still pending
- `tbd` — Tier 2 KDoc backfill — backtest reporting types, risk types, portfolio supervisor, indicator catalog. Phase 22 covered Tier 1; Tier 2 lands organically as features ship per the SKILL rule.

---

## Performance and hardening (post-go-live)

- `tbd` — Speed and latency improvements (profiling-driven)
- `tbd` — Memory and GC tuning
- `tbd` — Virtual threads where applicable
- `tbd` — GraalVM native image for the CLI
- `tbd` — JNI for hot paths if profiles show wins
- `tbd` — Stress tests, soak tests, chaos tests
- `tbd` — Bundle/binary size reduction

---

## Future

- `tbd` — News pipeline: Twitter, Yahoo, economic calendar, per-symbol curation; integrates into DSL
- `tbd` — AI strategy-tuning pipeline: train on a strategy, derive best configs, agent layer for buy/sell decisions, continuous retraining
- `tbd` — Portfolio v3: `WEIGHT` clause for capital allocation, import-time overrides, nested portfolios (carryover from Phase 14 known limitations)
- `tbd` — Symbol watch dynamic extension: how strategies declare and acquire data for a totally new symbol at runtime
- `tbd` — Editor tooling: parser/linter/index/highlighting/autocomplete/template generation for `.qkt` files
- `tbd` — Landing page

---

## How to maintain this file

- Update the status marker in place (`done` ↔ `progress` ↔ `tbd`).
- When an item ships, leave it in its current section but flip to `done`. Move to the **Done** section only at the next major housekeeping pass.
- New items go into the section that matches their priority. If unsure between immediate and future, default to future.
- Keep entries one line each. If an item needs more detail, link to its spec/plan/changelog.
