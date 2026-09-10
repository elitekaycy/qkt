# Forge FVG/sweep faithful gateability — design spec

- **Date:** 2026-07-26
- **Status:** proposed
- **Source research:** `docs/research/2026-07-26-intraday-fvg-sweep-edge-study.md`
- **Scope:** qkt runtime, qkt DSL execution, and qkt-forge evidence requirements needed
  to test passive FVG/liquidity-sweep retest strategies faithfully.

## 1. Problem

The intraday FVG/liquidity-sweep research found no gate-worthy edge under the
tested mechanical rules. During that work, qkt-forge strategy attempts also
exposed a separate platform issue: the exact colleague-style rule cannot
currently be proven or rejected through qkt-forge gates without changing the
trading rule.

The faithful rule needs all of these at the same time:

- first/last 10-minute hour/session filters;
- passive retest entries at dynamic prices such as the 50% FVG level, event
  candle wick, or higher-timeframe level;
- dynamic protective stops based on ATR, event-candle structure, or prior
  range extremes;
- spread/R stand-down based on bid/ask spread relative to the chosen stop
  distance;
- optional previous-day/previous-week levels while executing on 1m or 5m bars;
- no repeated pending-order stacking from the same signal.

The current qkt surface can express parts of that, but qkt-forge evidence showed
the combination is not gateable faithfully.

## 2. Evidence from the research pass

Candidate files on `bot2:/root/projects/qkt-forge/strategies/research`:

- `eurusd_low_cost_hour_session_sweep_fade_v1.qkt`
- `eurusd_low_cost_fvg_short_liquid_control_v1.qkt`
- `eurusd_atr_proxy_hour_session_sweep_fade_v2.qkt`
- `eurusd_atr_proxy_fvg_short_liquid_control_v2.qkt`
- `eurusd_static_stop_hour_session_sweep_fade_v3.qkt`
- `eurusd_static_stop_fvg_short_liquid_control_v3.qkt`
- `eurusd_market_retest_hour_session_sweep_fade_v4.qkt`
- `eurusd_market_retest_fvg_short_liquid_control_v4.qkt`
- `eurusd_5m_session_fvg_short_market_retest_v5.qkt`
- `eurusd_1m_hour_edge_previous_week_sweep_short_v6.qkt`
- `eurusd_5m_session_rolling_week_sweep_long_v8.qkt`

Observed blockers:

1. Dynamic bracket stop expressions parse in documented examples, but the
   qkt-forge backtest path rejected dynamic stop trigger expressions such as
   `STOP_LOSS AT eur.low ...`, `STOP_LOSS AT eur.close ...`, and
   `STOP_LOSS BY atr(...)` at runtime with `unsupported trigger expression`.
2. The qkt bar-screen surface did not expose a usable per-bar strategy spread
   field. Conditions using `eur.spread` parsed but did not behave as a reliable
   spread/R stand-down.
3. Passive pending-limit proxies repeatedly stacked/re-fired orders. A Q1 2018
   check produced 338 qkt trades versus 16 long and 17 short comparable harness
   entries.
4. `EVERY 1w` was not supported. Previous-week logic required same-stream
   rolling workarounds or external preprocessing.
5. Mixed-timeframe bar sync rejected a 5m execution stream with a 1d level
   stream: `SyncGroupKey members must share the same timeframe`.
6. qkt-executable market-entry approximations were negative or materially
   different from the passive retest harness. They are valid rejections of their
   own rules, not a faithful proof about the original passive rule.

## 3. Goals

Make a future qkt-forge candidate from this strategy family gateable only when
the exact rule is executable and auditable end to end.

Required capabilities:

1. Dynamic bracket exits must execute in backtest and live using the same
   expression semantics.
2. Strategy code must be able to access bid/ask spread, or an equivalent
   per-event execution-cost estimate, in conditions.
3. Pending orders must be de-duplicable by signal identity or bounded by an
   explicit working-order guard.
4. Higher-timeframe levels must be expressible for lower-timeframe execution
   without unsafe mixed-timeframe behavior.
5. qkt-forge reports must bind the strategy, parameters, instrument metadata,
   data snapshot, and execution preset strongly enough for real-money review.

## 4. Non-goals

- Do not add a bespoke FVG or liquidity-sweep indicator as the first fix. The
  issue is gateability and execution fidelity, not a missing signal primitive.
- Do not make G1-G7 approximate research screens more permissive. Approximate
  tiers may reject candidates, but promotion evidence must come from exact
  tick-aware replay once a candidate is executable.
- Do not weaken `SyncGroupKey` by silently merging arbitrary timeframes. Mixed
  timeframe support needs deterministic alignment semantics.

## 5. Capability A: dynamic bracket exits

### Current failure mode

The DSL documentation shows dynamic stops such as:

```qkt
STOP_LOSS AT btc.close - atr(btc, 14) * 2
```

The research qkt candidates could parse similar constructs, but qkt-forge
backtests rejected them at runtime in the trigger path.

### Required behavior

For a bracket order, `STOP_LOSS AT <price-expr>` and
`TAKE_PROFIT AT <price-expr>` must support price expressions over the current
closed signal bar and deterministic indicator state.

Evaluation rule:

- evaluate the stop/target price exactly once when the parent order is created;
- persist the resolved absolute price on the order request;
- downstream broker/backtest trigger handling receives an ordinary fixed price;
- live and backtest share the same resolved order request shape.

This avoids path-dependent re-evaluation of the stop while still supporting
ATR/structure stops at entry time.

### Acceptance tests

- A qkt strategy with `STOP_LOSS AT stream.close - atr(stream.candle, 14) * 2`
  parses and backtests without `unsupported trigger expression`.
- The generated order request contains the resolved absolute stop price.
- Backtest and live-order translation tests prove the same resolved value is
  used for paper, mt5-sim, and MT5 translation boundaries.

## 6. Capability B: spread/R access

### Current failure mode

The research harness could compute median entry/exit spread from bid/ask-derived
bars, but qkt strategy conditions did not have a reliable `spread` field in the
screen path.

### Required behavior

Expose one deterministic condition-side value per stream:

```qkt
stream.spread
```

Semantics:

- on bid/ask bars, `spread` is the bar-close spread or a documented aggregate
  chosen by the bar builder;
- on exact ticks, `spread = ask - bid` for the current quote;
- if spread is unavailable, the value is `null` and comparisons evaluate false;
- reports disclose whether spread was available for every stream.

Risk-normalized gating can then be written as:

```qkt
WHEN stream.spread / abs(entryPrice - stopPrice) <= 0.10 THEN ...
```

### Acceptance tests

- Bar backtest fixture with bid/ask data exposes non-null `stream.spread`.
- Tick backtest fixture exposes quote-level spread.
- Missing-spread fixture makes a spread comparison false and adds a report
  warning.

## 7. Capability C: pending-order de-duplication

### Current failure mode

Passive retest qkt proxies stacked orders from repeated signal bars. Position
guards and cooldown checks did not reliably bound working pending orders to one
per signal.

### Required behavior

Add an explicit strategy-visible working-order guard and/or signal-scoped order
identity:

```qkt
PENDING.stream = 0
```

or:

```qkt
BUY stream LIMIT AT entry TAG signalKey
```

Minimum acceptable behavior:

- a strategy can test whether it already has a working order for a stream;
- repeated closed bars from the same event cannot create unbounded duplicated
  orders when guarded;
- the report includes pending order create/fill/cancel counts by strategy and
  stream.

### Acceptance tests

- A fixture with one qualifying retest signal across several bars creates one
  pending order, not one order per bar.
- Cancelling or filling the pending order releases the guard deterministically.
- Restart persistence preserves the working-order guard state.

## 8. Capability D: higher-timeframe levels on lower-timeframe execution

### Current failure mode

The research found weak previous-week/day sweep candidates, but qkt could not
faithfully express previous-week levels for 1m/5m execution:

- `EVERY 1w` was unsupported;
- 1d + 5m sync was rejected because sync group members must share one
  timeframe.

### Required behavior

Support one of these deterministic surfaces:

1. native weekly bars:

   ```qkt
   DATA wk = EXNESS:EURUSD EVERY 1w
   ```

2. prior-period indicators on the execution stream:

   ```qkt
   previous_day_high(stream.candle)
   previous_week_low(stream.candle)
   ```

3. explicit slow-anchor mixed-timeframe sync where the lower timeframe can read
   the latest completed higher-timeframe value without requiring equal bar
   boundaries.

The prior-period indicator route is the smallest safe surface for this strategy
family because it avoids arbitrary mixed-timeframe ordering.

### Acceptance tests

- 1m strategy can reference previous UTC day/week high/low without mixed
  timeframe sync.
- Values update only after the higher-timeframe period closes.
- Weekend/holiday gaps are deterministic and covered by fixtures.

## 9. Capability E: immutable qkt-forge evidence bundles

### Current state

The inspected gate stack is conceptually strong for qkt-executable strategies:
G1-G7 provide screening, validation, walk-forward, significance, robustness, and
portfolio checks; G8 performs tick-fills with `mt5-realistic` execution. The
problem is evidence strength for real-money review.

Research audits showed sampled cached bars match raw ticks, but qkt report
metadata still describes a mutable local dataset with `allow-incomplete`.

### Required behavior

Every promoted qkt-forge gate run must save:

- strategy file hash;
- qkt binary/container image digest;
- qkt-forge commit;
- instrument metadata hash;
- gate config hash;
- execution preset;
- data snapshot manifest hash;
- tick/bar coverage report;
- fill tape with broker model and cost adjustments.

If the data snapshot is mutable or incomplete, the gate may reject but must not
label the result real-money-trustworthy.

### Acceptance tests

- A gate run writes a single manifest tying all hashes to the result.
- Re-running against a changed data file changes the data snapshot hash.
- G8 reports exact tick-fill settings and rejects missing fill tapes.

## 10. Gate policy for this strategy family

Until the capabilities above exist, the promotion rule is:

1. G1-G7 may be used only to reject qkt-executable approximations.
2. A positive G1-G7 result is not sufficient if the qkt strategy differs from
   the research rule by entry type, stop model, spread gate, or HTF level logic.
3. A candidate may enter the full qkt-forge gate funnel only after:
   - qkt parse passes;
   - qkt screen trade count is close to the research harness count;
   - qkt screen PnL/PF/Sharpe are positive on development and yearly slices;
   - exact G8 tick/mt5-realistic replay remains profitable;
   - the evidence bundle proves immutable data and exact execution settings.

## 11. Implementation order

1. Dynamic bracket exit resolution at order creation.
2. Strategy-visible spread access and report warnings for unavailable spread.
3. Pending-order working guard or signal-scoped de-duplication.
4. Previous-day/week level indicators on same execution stream.
5. qkt-forge immutable evidence bundle enforcement.

This order prioritizes the blockers that directly prevented faithful FVG/sweep
strategy testing.
