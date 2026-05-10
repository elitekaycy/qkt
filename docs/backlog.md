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

---

## Immediate (blocks going live with real money)

- `done` — DSL `LOG` action with levels, placeholders, structured fields; stdout `[strategy]` prefix; child file slash-safe names (see [phase 15](phases/phase-15.md))
- `done` — Backtest fidelity audit: equity curve, DD-days, Monte Carlo, per-trade risk in HTML report (slippage/spread/regime deferred) (see [phase 16](phases/phase-16.md))
- `done` — Backtest vs live execution parity: identical trades (orderId/symbol/side/qty/price/timestamp) between Backtest and LiveSession given same compiled strategy and tick sequence (`src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`)
- `done` — Backtest report HTML output: single self-contained `report.html` (see [phase 16](phases/phase-16.md))
- `tbd` — Tick-feed accuracy audit: compare current TradingView and Bybit feeds against known-good sources; confirm WS reconnect/resume/dedup is prod-grade
- `tbd` — End-to-end portfolio daemon test (deferred from Phase 14 — see `docs/phases/phase-14.md` known limitations)
- `tbd` — Pristine test sweep + memory leak audit: every code path covered, no stubs, no resource leaks across long-running sessions
- `tbd` — Documentation MVP: KDoc + Dokka for code reference, MkDocs Material for end-user docs, Mermaid for architecture diagrams, served via GitHub Pages with auto-deploy CI

---

## Sequence-after-immediate (still pre-live, days not weeks)

- `progress` — MT5 broker (multi-profile via mt5-gateway): client/translator/poller/state-recovery shipped; `qkt brokers list` works; LiveSession → MT5 dispatch refactor deferred (see [phase 17](phases/phase-17.md))
- `tbd` — Standardized logging across engine/strategy/child/portfolio: consistent prefix or structured JSON; aligned with DSL `LOG` output format
- `tbd` — Packaging and one-shot install: Docker image, single binary or self-contained zip, documented `qkt up` on a fresh machine
- `tbd` — CI/CD: GitHub Actions for build, test, ktlint, release artifacts, docs deploy

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
