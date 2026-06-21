# DSL capability-gap batch — design

Date: 2026-06-21
Status: accepted
Issues: #479, #501, #502, #503, #504, #505, #506, #507, #508 (all `capability-gap` + `qkt-forge`)

## Context

qkt-forge files `capability-gap` issues when its strategy-authoring agent believes
the DSL cannot express an edge it discovered. History (forge issue-audit 2026-06-15)
shows a recurring failure mode: the authoring agent's grammar is **stale**, so it
re-files constructs that already exist under new names. So every gap is audited
against the *current* DSL before any engine change. This batch splits the nine open
gaps into "already expressible" (close + teach forge) and "real" (build).

## Audit result

### Already expressible — no engine change (close with a working-DSL proof)

- **#507 minute-of-hour / top-of-hour gate.** `NOW.MINUTE_UTC` already exists
  (`NowField.MINUTE_UTC`, 0–59 UTC; proven by `NowAccessorEvalTest`). Top-of-hour
  entry = `NOW.MINUTE_UTC = 0`; force-flat = `NOW.MINUTE_UTC >= 4`. The issue's
  premise ("NOW exposes .weekday but no .minute") is false against current code.
- **#502 NR7 / inside-bar.** `.high`/`.low`/`.open` are exposed to expressions, and
  `HIGHEST`/`LOWEST` accept **arbitrary expressions** (not just `.close`) with a
  built-in one-bar lag (the `PriorBars` wrapper). So: per-bar range = `g.high-g.low`;
  NR7 = `(g.high-g.low) < LOWEST(g.high-g.low, 6)`; inside-bar =
  `g.high < HIGHEST(g.high, 1) AND g.low > LOWEST(g.low, 1)`; the narrow day's
  high/low as breakout levels = `HIGHEST(g.high,1)` / `LOWEST(g.low,1)` on the next bar.
- **#505 rejection-wick half.** `.open/.high/.low/.close` are exposed, so wick anatomy is
  expressible: upper wick = `g.high - max(open, close)`. `MAX`/`MIN` are *windowed-aggregate*
  keywords (not scalar), so the body-extreme is a `CASE`: upper wick =
  `g.high - (CASE WHEN g.open > g.close THEN g.open ELSE g.close END)`. (#505's *modulo* half
  is a real gap — see #501.)

### Real gaps — build

| Issue(s) | Capability | Vehicle |
|---|---|---|
| #501, #505 | round-number / grid math | `FuncRegistry`: `MOD`, `FLOOR`, `ROUND`, `CEIL` |
| #504 | rolling realized-vol percentile gate | `PERCENTILE_RANK` indicator |
| #503, #506 | session-anchored VWAP + bands | `VWAP_SESSION` + `VWAP_SESSION_STDEV` indicators |
| #508 | session-anchored range as price levels | `SESSION_RANGE_HIGH` / `SESSION_RANGE_LOW` indicators |
| #479 | cross-symbol confirmation ratio | `CONFIRM_RATIO` multi-series indicator |

## Design decisions

The whole batch fits qkt's existing extension points — `FuncRegistry` for scalar
math, `IndicatorRegistry` for rolling indicators, and the `RESID` multi-series
compiler path for the cross-symbol aggregate. No new `ExprAst` node, so **zero**
exhaustive-`when`-site churn.

### #501/#505 — scalar grid math

Add four primitives to `FuncRegistry`, computed in exact `BigDecimal` (no `toDouble`,
to keep grid math precise):

- `MOD(a, b)` — floored modulo, `a - b*floor(a/b)`; result has the sign of `b`, so for
  a positive grid step it lands in `[0, b)`. e.g. `MOD(1.2034, 0.0050) ≈ 0.0034`.
- `FLOOR(x)`, `CEIL(x)`, `ROUND(x)` — to the nearest integer (`ROUND` is half-up).

These compose into everything #501/#505 need: nearest grid level =
`ROUND(price/step)*step`; distance-to-figure = `ABS(price - ROUND(price/step)*step)`;
breach test = `MOD(price, step) < eps OR MOD(price, step) > step - eps`. We expose
primitives, not a bespoke `dist_to_figure` (qkt philosophy: primitives compose).

### #504 — PERCENTILE_RANK

`percentile_rank(<series>, <lookback>)` → fraction of the trailing `lookback` window
**strictly below** the current value, in `[0,1]`. Distribution-free, so on a bimodal
realized-vol series `percentile_rank(stddev(xag.close, 30), 200) < 0.5` cleanly selects
the calm half — which `zscore` cannot (its mean sits in the empty trough). Rolling
`ArrayDeque` window like `Stddev`; the current value is included in the denominator
count (count-below / window-size).

### #503/#506 — session-anchored VWAP

Two **candle-fed** indicators (`CANDLE_SERIES`, `requiresVolume = true`):

- `vwap_session(<stream>.candle, <anchorHour>)` — volume-weighted average of typical
  price `(h+l+c)/3`, accumulated since the most recent `anchorHour:00` UTC and reset
  each day at that hour. `anchorHour=0` anchors at UTC midnight (the #503 session-open
  case); `anchorHour=12` anchors at the overlap (the #506 case).
- `vwap_session_stdev(<stream>.candle, <anchorHour>)` — volume-weighted standard
  deviation of typical price around the running session VWAP.

Bands compose: `vwap_session(g.candle,12) + 2*vwap_session_stdev(g.candle,12)`. The
reset is derived **purely from the candle's own `startTime`** (session index =
`floorDiv(startTime - anchorHour*3600_000, 86_400_000)`), so it is deterministic and
needs no clock injection — backtest and live read identical logic. O(1) per candle.

**Parity caveat (documented, not blocking):** volume is present in backtest data
(dukascopy carries it; `Candle.volume` is mandatory) but the MT5 gateway sends
`volume = null` for quote-driven FX/metals, so a session-VWAP strategy is
backtest-faithful yet inert live on MT5 — the *same* limitation the existing `VWAP`
and `OBV` already carry. The fix is gateway-side (forward MT5 tick volume) and is out
of scope here; forge backtests, so the capability unblocks its candidates today.

### #508 — session-anchored range

Two candle-fed latching indicators:

- `session_range_high(<stream>.candle, sh, sm, eh, em)` — the **high** of the most
  recent *completed* instance of the UTC window `[sh:sm, eh:em)`, held constant as a
  price level until the next instance completes.
- `session_range_low(...)` — the symmetric low.

Window membership and wrap-midnight follow `SESSION_WINDOW` semantics. The indicator
accumulates the running high/low while inside the window and **latches** it the first
time a candle arrives outside the window — so the prior Asian range (00:00–07:00) is
available throughout the later London window (07:00–11:30) the same day. Mid and width
compose: mid = `(high+low)/2`, width = `high-low` (the issue's SL = `0.5*width` and
TP = `mid`). We expose high/low primitives only; forge's grammar gets the mid/width
idioms. Value is null until the first window completes (a warmup delay, not a bug).

### #479 — CONFIRM_RATIO

The only multi-series build. Follows the `RESID` compiler path (special-cased in
`ExprCompiler`, bound via `bindMulti`, backed by a `MultiIndicator`):

`confirm_ratio(<signal>, <peer1>, <peer2>, …, <lookback>)` → fraction in `[0,1]` of
peers whose return over `lookback` bars is **same-signed** as the signal's return over
the same window. The signal is the first series; the trailing integer literal is the
lookback; everything between is a peer series.

**Polarity folds into the peer expression** via negation rather than a parallel
`polarity[]` list: an inverse pair like USDCHF is written `-usdchf.close`, whose return
sign is the negation of USDCHF's. This keeps the surface a flat series list (matching
`RESID`), avoids new list-pair syntax, and composes. So the #479 thesis becomes
`confirm_ratio(eur.close, gbp.close, aud.close, -usdchf.close, 4) < threshold`.

## Testing

- Unit tests per indicator class (`PercentileRankTest`, `VwapSessionTest`,
  `VwapSessionStdevTest`, `SessionRangeTest`, `ConfirmRatioTest`) modelled on
  `StddevTest`/`VWAPTest`/`OlsResidualTest` — deterministic, exact `BigDecimal`
  assertions, AssertJ, no mocks.
- `FuncRegistryTest` extended for `MOD`/`FLOOR`/`ROUND`/`CEIL` incl. arity errors.
- `RegistryNamesTest` extended with the new indicator names.
- DSL-level compile tests proving the three closures (#507/#502/#505-wick) compile via
  `Parser` + `AstCompiler`, doubling as regression guards and issue-close evidence.
- At least one end-to-end backtest test exercising a new indicator through the engine
  to uphold Backtest=Live parity.

## Out of scope / deferred

- MT5 gateway tick-volume forwarding (unblocks live session-VWAP) — gateway repo.
- `polarity[]` list syntax, `dist_to_figure`/`confirm_count` convenience wrappers —
  YAGNI; the primitives above compose.
- Session-range mid/width as their own DSL names — composable from high/low.

## Forge follow-up

After merge, update `qkt-forge` `authoring.py` grammar/examples with: the new five
constructs, plus the previously-unknown-to-forge `NOW.MINUTE_UTC`, `.open/.high/.low`
accessors, `HIGHEST`/`LOWEST` over expressions, and the wick idiom. Parse-validate
against the rebuilt `:edge`, then rerun the loop.
