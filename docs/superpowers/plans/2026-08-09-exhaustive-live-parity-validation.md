# Exhaustive Live Parity Validation Plan

**Goal:** Produce falsifiable, retained evidence for every supported qkt capability across raw
ticks, ordinary bars, tick-resolved bars, live-paper, the local Exness demo gateway, reporting,
journals, portfolios, and QKT Insights before any external strategy is promoted.

**Spec:** `docs/superpowers/specs/2026-08-09-exhaustive-live-parity-validation-design.md`

- [x] Add a machine-readable capability catalog and a CI test that detects registry/runtime drift.
- [ ] Inventory existing evidence without upgrading parser, construction, or vacuous tests to
      behavioral proof.
- [ ] Add independent numeric/readiness oracles for every indicator output and DSL numeric function.
- [ ] Add non-vacuous DSL trace scenarios for every expression, state reference, action, schedule,
      sequence, session, basket, and supported interaction.
- [ ] Generate temporary `.qkt` strategies plus config, risk, book-risk, identity, expected-trace,
      and cleanup artifacts for every applicable live scenario, including supported configless use.
- [ ] Run each applicable scenario over raw ticks, independently constructed bars, ordinary bar
      replay, tick-resolved bars, and live-paper at all supported timeframe boundaries.
- [ ] Cover every order type, time in force, partial/rejected fill, cancel, expiry, bracket, OCO, OTO,
      trailing, stack, scale-out, timed exit, close, resize, halt, and recovery lifecycle.
- [ ] Reconcile fills, positions, cash, equity, realized/unrealized PnL, spread, commission, fees,
      swap, reports, manifests, and journals for every applicable scenario.
- [ ] Validate multi-symbol, multi-timeframe, multi-strategy, and portfolio isolation and aggregation.
- [ ] Add a localhost-only Exness demo harness with account allowlisting, bounded 0.01-lot exposure,
      deterministic correlation, mandatory cleanup, and final reconciliation.
- [ ] Measure sustained gateway polling, routing, latency, queueing, resource trend, reconnect,
      restart, resynchronization, and already-deployed behavior without restricting JVM heap.
- [ ] Run staged multi-container QKT concurrency with isolated state and prove account-wide
      reconciliation plus strict per-container, strategy, and book ownership.
- [ ] Validate QKT Insights exact delivery, retry behavior, and strict strategy/book attribution.
- [ ] Fix every discovered engine defect with focused regression and adversarial edge-case tests in
      this concern's PR.
- [ ] Repeat the entire required matrix after the final fix and retain checksummed evidence.
- [ ] Complete the documented 30-day demo burn-in with no unexplained state or reconciliation gaps.
- [ ] Adapt the downloaded strategies only after validation passes, then build three or four
      independently monitored books and promote them in measured demo waves.
- [ ] Merge by PR through `dev -> testing -> main` only after every required evidence gate passes.
