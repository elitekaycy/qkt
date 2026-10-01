# Phase 43.4: option chain analytics streams — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A strategy can read implied-volatility analytics of a stored option chain as an ordinary
stream: `iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h`, then `WHEN iv.close > 60 THEN …`. It
uses every existing indicator, and a value that cannot be computed honestly is absent, never invented.

**Architecture:**
- **Symbol.** `CHAIN:<VENUE>.<ROOT>.<metric>.<tenor>` is an observation stream like `HUB:` and
  `MACRO:`. It parses as a dotted symbol, is read-only, is not provisioned from the tick store, and
  makes event candles. `ChainAnalyticsSymbol` parses and validates it: the root is declared under
  `options:` with a chain series, the metric is known, and the tenor is `<N>d` with N > 0.
- **Metrics.** `ChainAnalytics` is pure, over one `ChainSnapshot`, the catalog's contracts and the
  root's terms. It uses only quotes with a mark IV and a mark at most `maxQuoteAgeMinutes` old. Per
  expiry:
  - the forward F is the median `underlying` of its usable quotes (the book series' per-expiry
    forward, or the trade series' index at each trade);
  - T is `(expiry − t)` in years of 365 days, and must be > 0;
  - ATM IV is the mean mark IV of the usable quotes at the listed strike nearest F (ties go to the
    lower strike).
- **Tenor interpolation.** At tenor τ, total variance `IV²·T` is interpolated linearly in T between
  the two expiries bracketing τ. An expiry exactly at τ is used as it is. τ outside the listed range
  is absent (no extrapolation).
- **`skew_25d`.** Per expiry, each usable quote's Black-76 delta uses F, K, T, the row's rate (or 0)
  and σ = mark IV / 100. The 25-delta put IV and call IV are each interpolated linearly in delta
  between the two quotes bracketing −0.25 and +0.25. The skew is put minus call, in IV points.
  Across expiries it is interpolated linearly in T. Any missing piece leaves it absent.
- **Units.** IVs are in percent (Deribit's convention), so `atm_iv` 48.7 means 48.7%.
- **Data.** `ChainAnalyticsMarketSource` (TICKS and BARS: flat candles for warmup, like HUB) emits
  one tick per stored snapshot of the root's series where the metric is defined, routed by prefix in
  `StoreMarketSource`.
- **`put_call_oi`** is dropped from the spec's metric list: neither free source carries open
  interest per snapshot (spec amendment).

**Spec:** §6.4 (amended: `CHAIN:` prefix, metrics `atm_iv` and `skew_25d`); §6.2 pricing.

## Global constraints

- No change to any existing AST or fingerprint. The parse pins (`DslCorpusPinTest`) and golden
  backtests stay green.
- No new `TokenKind`. `CHAIN` is a broker identifier, not a keyword.
- Every place that treats `MACRO:`/`HUB:` as observations also treats `CHAIN:` that way:
  `isObservationSymbol`, `ReadOnlyOrderCheck`, tick provisioning and feed requirements.
  `CHAIN:` symbols never reach a broker route.
- Files ≤ 200 lines; KDoc; commits via the helper; values recomputed independently in tests.

## Review focus

- A chain where the tenor falls outside the listed expiries, or only stale quotes remain: the stream
  is absent at that instant, never a stale or extrapolated number.
- An expiry whose only usable quotes are calls (no puts), for `skew_25d`: that expiry is skipped and
  the skew is absent unless both of the expiries interpolated across have both wings.
- `BUY iv` on an analytics alias: refused as read-only at compile time.
- A run with only a `CHAIN:` stream and an option contract: the calendar, provisioning and broker
  routing ignore the analytics symbol.
- A negative skew value passes the tick floor (the stream is an observation).

---

### Task 1: ChainAnalytics (pure) with real-snapshot tests
- ATM IV per expiry, variance interpolation, skew from deltas; tests on a recorded real book
  snapshot (fixture with provenance), with expected values computed independently in Python and
  pinned with their derivation.

### Task 2: CHAIN symbol, parsing and observation plumbing
- `ChainAnalyticsSymbol`; the parser accepts dotted `CHAIN:` symbols; `isObservationSymbol`,
  read-only check, provisioning and feed filters; a `BUY` on an analytics alias is refused.

### Task 3: ChainAnalyticsMarketSource, routing and setup checks
- Ticks per snapshot where defined, flat bars for warmup, prefix route, a setup failure for an
  unknown root, metric or tenor, and coverage through `OptionChainCoverage`.

### Task 4: end to end and docs
- A strategy on a real trade-chain day reads `atm_iv` and trades an option on it; docs in
  `reference/dsl/` and the how-to, parity row (backtest only), spec amendment.
