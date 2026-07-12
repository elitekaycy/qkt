# Backtest Swap Financing Plan

**Goal:** Model and report deterministic broker swap cash flows in backtests while
preserving the shared accounting and risk pipeline.

**Spec:** `docs/superpowers/specs/2026-07-12-backtest-swap-financing-design.md`

- [x] Add validated swap metadata and YAML parsing.
- [x] Add readable DSL stream metadata and tests.
- [x] Implement a boundary-driven financing book with signed point-rate conversion.
- [x] Route accrual through the trading pipeline and daily risk state.
- [x] Add global/per-strategy/daily reporting and output serialization.
- [x] Add end-to-end rollover, triple-day, weekend, gap, hedge, and risk tests.
- [x] Update instrument schema, backtest model, parity, and rollout documentation.
- [ ] Run targeted tests, full build/test, hygiene, PR, and promotion gates.
