# DSL quant-completeness & data-structures research

**Date:** 2026-06-08
**Scope:** Two questions. (1) Does the DSL give a quant author the maths, indicators,
operators, functions, datatypes, and composition needed to write the strategies they want —
and is it cheap to extend? (2) Do strategies need first-class data structures and algorithms
("DSA"), or does the current model already cover it?

The two questions converge on one answer, so they're treated together.

---

## TL;DR

- **Completeness (Q1): strong core, cheap to extend, one real gap.** The language covers
  trend / momentum / volatility / volume / breakout / mean-reversion cleanly, composes well
  (nested indicators, expression-fed indicators, `LET`, snapshots, windowed aggregates), and a
  new indicator or function is a **~10-line registry entry**. The one genuine gap is
  **cross-series statistics** (correlation, regression slope, z-score of a spread) — the maths
  that pairs / stat-arb strategies are built on.
- **DSA (Q2): the DSL is deliberately not a general-purpose language, and that is correct.**
  No user-defined data structures, no mutable variables, no runtime loops. Strategies stay
  static (analyzable at compile time) and bounded (no unbounded memory/runtime). The "DSA for
  quant" need is real but should be served by **adding specific algorithmic primitives**, each
  implemented with the right data structure *under the hood* (the codebase already does this —
  `RollingHigh`/`RollingLow` use O(1) monotonic deques; aggregates use ring buffers) — **not**
  by exposing raw heaps / maps / loops to authors.
- **Net recommendation:** keep the language declarative; extend it by adding primitives, not
  escape hatches. The highest-value additions are the cross-series statistical primitives,
  which close both the completeness gap and the "real algorithm" need at once.

---

## Q1 — Completeness inventory

### Indicators (15 built in)

| Category | Indicators |
|---|---|
| Trend / MA | SMA, EMA, WMA |
| Momentum | RSI, MACD (`MACD` / `MACD_SIGNAL` / `MACD_HIST`) |
| Volatility | ATR, Stddev, Bollinger (`UPPER`/`MIDDLE`/`LOWER`) |
| Volume | VWAP (tick-fed) |
| Range / breakout | HIGHEST, LOWEST (Donchian, O(1) monotonic deque) |
| Session reference | SessionHigh, SessionLow, PreviousDayHigh, PreviousDayLow |

Registered in `dsl/stdlib/IndicatorRegistry.kt`; bound to streams/expressions in
`dsl/compile/IndicatorBinding.kt`. Three input kinds: `NUMERIC_SERIES`, `CANDLE_SERIES`
(OHLC, e.g. ATR), `TICK_SERIES` (e.g. VWAP).

**Composition is a strength.** Indicators nest (`ema(ema(close,9),21)`) and consume arbitrary
expressions (`stddev(gold.close - 75*silver.close, 60)`) — so a spread's volatility is one line.
Multi-output indicators (MACD, Bollinger) share one underlying instance across their DSL names.

### Expression language

- **Operators:** arithmetic `+ - * /` and unary `-`; comparison `< <= > >= == !=`; logical
  `AND OR NOT`; domain `CROSSES ABOVE/BELOW`, `BETWEEN`, `IN`, `IS [NOT] NULL`. (No `%`/modulo
  in expressions.)
- **Functions:** `ABS SQRT LOG EXP POW MIN MAX`; windowed aggregates `MAX/MIN/MEAN/SUM(series)
  SINCE {OPEN | T-N}`.
- **Conditionals:** `CASE WHEN … THEN … ELSE … END`.
- **State / time:** `NOW.HOUR_UTC / MINUTE_UTC / WEEKDAY / DATE_UTC / EPOCH_MS`; rich
  `ACCOUNT.*` and `POSITION.<stream>.*` accessors (pnl, drawdown, streaks, counts, MFE, holding
  duration); `@` snapshots (`price@buy`).
- **Types:** runtime union `Num / Bool / Str / Undefined` (no static type system; `Undefined`
  propagates and is testable with `IS NULL`). Duration literals `1s/30m/2h/1d`. Money-precision
  `BigDecimal` throughout.
- **Composition / preprocessor:** `LET`, `PARAM`, `DEFAULTS`, `IMPORT … AS … [HOLD]` (portfolio),
  `SYNCHRONIZE … WITHIN`, `SCHEDULE`.

### Gap analysis

| Missing | Class | Why it matters | Cost to add |
|---|---|---|---|
| **Correlation / covariance** (cross-series) | statistical | pairs / stat-arb core | moderate (multi-input) |
| **Linear-regression slope / intercept** | statistical | trend fit, beta, hedge ratio | moderate |
| **Z-score** (of series or spread) | statistical | mean-reversion entries | low (compose) or primitive |
| **Rolling percentile / median** | statistical | robust thresholds, regime | moderate (needs DS) |
| Stochastic (%K/%D), ADX, CCI, Williams %R | momentum osc. | common screens | ~10 LoC each |
| Keltner Channels | volatility | ATR-band analogue to Bollinger | ~10 LoC |
| OBV | volume | volume-trend confirmation | ~10 LoC |
| Variance (exposed), HMA/DEMA/TEMA | misc | convenience | ~10 LoC each |

**Verdict:** for single- and multi-stream **technical** strategies, the language is effectively
complete and trivially extensible. The one structural gap is **cross-series statistics**
(correlation, regression, z-score, rolling percentile) — the maths stat-arb/pairs strategies
need. Everything else is a cheap registry add to be done on demand, not up front (YAGNI).

### Extensibility (how cheap is "the next primitive")

Measured from the lexer → parser → AST → compiler → runtime trace:

| Construct | Touches | Effort |
|---|---|---|
| New indicator | registry entry | ~10 LoC |
| New function | registry entry | ~10 LoC |
| New binary operator | token + parser case + AST + compiler case | ~50 LoC |
| New aggregate fn | token + AST enum + state case | ~30 LoC |
| New action/keyword (e.g. LATCH) | tokens + parse method + AST + compiler | ~500 LoC |

Adding indicators/functions/stats is the cheap quadrant — which is exactly where the gaps are.

---

## Q2 — Data structures & algorithms

### What an author can already express

The DSL ships DSA-flavoured primitives — each backed by a real data structure internally, but
exposed as a fixed, declarative construct:

| Primitive | Internal DS | Author writes |
|---|---|---|
| `HIGHEST/LOWEST(series,N)` | monotonic deque (O(1)) | rolling extremes |
| `MAX/MIN/MEAN/SUM … SINCE T-N` | ring buffer / accumulators | windowed aggregates |
| `price@buy[k]` snapshots | rolling deque per binding | replay captured values |
| `CROSSES ABOVE/BELOW` | prev-value state cell | edge detection |
| `STACK SPACING … WITHIN` | tier engine (MFE, fired/abandoned sets, time windows) | layered pyramiding |
| `LATCH … OFFSET … ARM` | trip-wire state machine + entry ladder | directional breakout machine |
| `FOR EACH s IN [...]` | compile-time unrolling | per-symbol rule fan-out |

`LATCH` + `STACK_AT` is the most algorithmic construct today: a state machine with
multi-dimensional guards (direction inference, anchor geometry, MFE thresholds, time windows)
and cascading order execution.

### What an author cannot express — by design

- No user-defined data structures (lists, queues, heaps, trees, maps).
- No mutable variables (state must flow through snapshots / aggregates / state machines).
- No runtime loops (`FOR EACH` is compile-time unrolling, not iteration over dynamic data).
- No order-book / L2 reconstruction; no arbitrary online algorithms.

**The DSL is intentionally not Turing-complete.** That buys three properties worth keeping:
strategies are **static** (fully analyzable at compile time), **bounded** (no unbounded memory
or runtime), and **predictable** (no author-authored infinite loop can wedge the live engine).

### The recommendation

Do **not** add general-purpose DSA (heaps/maps/loops/variables) to the author surface — it
would forfeit the analyzability and bounded-runtime guarantees that make a live trading DSL
safe to walk away from.

**Do** serve the real "I need an algorithm" cases by adding **specific primitives** that
implement the non-trivial data structure internally — the same pattern the codebase already
uses for rolling extremes. Highest-value candidates (which also close the Q1 stats gap):

1. **Rolling percentile / median** — internally an order-statistics structure (dual-heap or
   indexable skiplist). Serves robust thresholds and regime detection.
2. **Rank / top-K across streams** — internally a small bounded heap. Serves cross-sectional
   strategies ("long the top-K by momentum").
3. **Correlation / covariance / rolling beta across two streams** — internally streaming
   moment accumulators. Serves pairs / hedging.
4. **Online linear regression (slope/intercept/R²)** — internally streaming sums. Serves trend
   fit and hedge ratios.

Each is a bounded, analyzable, declarative primitive — fits the existing registry/binding
model, no new escape hatch.

---

## Proposed next actions

- **Issue A — cross-series statistical primitives** (closes the one real completeness gap and
  the "real algorithm" need at once): correlation/covariance, rolling beta, online regression
  slope, z-score of a spread, rolling percentile/median. Multi-input indicator binding is the
  main new machinery; the rest reuse the registry.
- **Issue B — momentum/volatility indicator gap-fill** (on demand, low priority): Stochastic,
  ADX, CCI, Williams %R, Keltner, OBV, variance, HMA/DEMA/TEMA — each a ~10-line registry entry,
  added when a strategy actually needs it.
- **Design note (no code):** record the decision that the DSL stays declarative / non-Turing-
  complete, and that algorithmic needs are met by specific primitives, not author-level DSA.

Files of record: `dsl/stdlib/IndicatorRegistry.kt`, `dsl/stdlib/FuncRegistry.kt`,
`dsl/compile/IndicatorBinding.kt`, `indicators/catalog/`, `indicators/Indicator.kt`.
