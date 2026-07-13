# Cross-Mode Parity Program Plan

**Goal:** Replace broad backtest/live parity claims with full-state, mode-spanning CI
evidence and explicitly identify the external MT5 fixture still required.

**Spec:** `docs/superpowers/specs/2026-07-12-cross-mode-parity-program-design.md`

- [x] Add a reusable DSL backtest/live-paper full-state parity harness.
- [x] Cover candle indicators, brackets, trail, GTD, CLOSE, RESIZE, latch, and stack.
- [x] Compare halt reason/scope/timestamp for daily-loss and drawdown paths.
- [x] Upgrade tick-resolved parity from selected regex fields to normalized full JSON.
- [x] Add one committed real-data tick day to tick-resolved parity.
- [x] Add CLI double-run determinism for paper, MT5 sim, bars, tick-fills, and portfolio.
- [x] Add a reviewed source-clock allowlist test.
- [x] Add the MT5 demo fixture schema and replay verifier.
- [x] Obtain and review an authentic MT5 demo capture; do not substitute synthetic data.
- [x] Update parity claims to cite exact test coverage and residual gaps.
- [ ] Run full gates, PR to dev, promote through testing/main, and close #645 only if every
      acceptance row, including the authentic MT5 fixture, is proven.
