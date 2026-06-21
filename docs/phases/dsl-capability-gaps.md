# DSL capability-gap batch

Status: shipped to `dev`. Spec: `docs/superpowers/specs/2026-06-21-dsl-capability-gaps-design.md`.
Plan: `docs/superpowers/plans/2026-06-21-dsl-capability-gaps.md`.

## Summary

Closes a batch of `capability-gap` issues filed by qkt-forge. Five were real engine gaps and
are now built; three were already expressible (forge's authoring grammar was stale) and are
proven with compile tests. Everything is deterministic and backtest=live, following the
existing `FuncRegistry` / `IndicatorRegistry` / multi-series patterns — no new AST node.

## What's new

Scalar functions (`FuncRegistry`):

- `mod(a, b)` — floored modulo; the round-number / grid-distance primitive.
- `floor(x)`, `ceil(x)`, `round(x)` — integer rounding (`round` is half-to-even, the Money convention).

Rolling indicators (`IndicatorRegistry`):

- `percentile_rank(value, lookback)` — distribution-free rolling rank, for bimodal regime gates.
- `vwap_session(stream, anchorHour)` — session-anchored volume-weighted average price.
- `vwap_session_stdev(stream, anchorHour)` — volume-weighted band stddev around the session VWAP.
- `session_range_high(stream, sh, sm, eh, em)` / `session_range_low(...)` — latched high/low of a
  prior completed UTC window, held as constant price levels.

Multi-series (compiler path, like `resid`):

- `confirm_ratio(signal, peer1, …, lookback)` — fraction of peers whose return confirms the
  signal's direction; negate a peer for inverse-pair polarity.

## Already expressible (no engine change)

- Minute-of-hour / top-of-hour gate → `NOW.MINUTE_UTC = 0` / `>= 4`.
- NR7 / inside-bar → `.high`/`.low` with `HIGHEST`/`LOWEST` over a range expression and the
  built-in one-bar lag (`HIGHEST(g.high, 1)` = yesterday's high).
- Rejection wick → `g.high - (CASE WHEN g.open > g.close THEN g.open ELSE g.close END)`.

## Usage

See `docs/reference/dsl/indicators.md` (sections: Confirmation ratio, Session-anchored
indicators, Percentile rank, Math helpers) for worked, copy-pasteable examples.

## Known limitations

- Session-VWAP needs per-bar volume. The MT5 gateway reports no volume for FX/metals, so a
  session-VWAP strategy is backtest-faithful but inert live on MT5 until the gateway forwards
  tick volume — the same limitation the existing `vwap`/`obv` carry.
- `max(a, b)` / `min(a, b)` are windowed aggregates, not scalar two-argument functions; use a
  `CASE` expression for the larger/smaller of two values.
- Session-range mid/width are not separate names — they compose: `(high+low)/2`, `high-low`.

## References

- Issues: #479, #501, #502, #503, #504, #505, #506, #507, #508.
- Indicators: `PercentileRank`, `SessionVwap`, `SessionRange`, `ConfirmRatio` under
  `com.qkt.indicators.catalog`; `MOD`/`FLOOR`/`CEIL`/`ROUND` in `com.qkt.dsl.stdlib.FuncRegistry`.
