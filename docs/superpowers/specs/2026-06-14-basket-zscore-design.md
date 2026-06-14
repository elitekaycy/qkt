# BASKET pseudo-symbol + cross-symbol ratio z-score — Design

> Scoping spec. Motivated by qkt-forge research (#457): gold's ratio against the
> antipodean commodity currencies (AUD, NZD) mean-reverts from extremes far more than
> against the pure majors (65-67% vs a ~52% coin-flip), and the edge needs two DSL
> primitives to express — a **synthetic equal-weight basket leg** and a **rolling pair
> z-score**. This spec defines both, resolves the price-definition contradiction in the
> issue, and grounds every decision in the existing engine. Tracked as **#457**;
> implementation plan at `docs/superpowers/plans/2026-06-14-basket-zscore.md` — review
> before building begins.

## 1. Purpose and the headline finding

The motivating strategy is a market-neutral mean-reversion spread: trade XAUUSD against an
equal-weight AUDUSD+NZDUSD basket, enter when the ratio z-score hits ±2, exit on reversion
to 0, stop at ±3.5, time-stop at ~2× the estimated half-life. Two capabilities are needed
**together**:

1. **`BASKET`** — a named equal-weight composite of bound symbols, usable as a
   pseudo-symbol: `antipodean = BASKET EQUAL_WEIGHT [aud, nzd]`. `antipodean.close` is the
   composite price; `BUY antipodean` / `SELL antipodean` fan out equal orders to every
   constituent; it is **one leg in the strategy's mental model** backed by N real orders.
2. **`zscore(expr, lookback)`** — rolling `(x - mean(x,n)) / stddev(x,n)` of any arithmetic
   expression of bound-symbol prices, a scalar usable in `WHEN` comparisons, e.g.
   `zscore(gold.close / antipodean.close, 100) >= 2.0`.

**Headline finding from the codebase deep-dive: `zscore` already ships.** There is a
`ZScore` indicator (`src/main/kotlin/com/qkt/indicators/catalog/ZScore.kt`), registered as
`ZSCORE` in `IndicatorRegistry` (arity 2), and the **expression-fed indicator binding**
(`IndicatorBinding.bindExpression`, #174) already lets the first argument be an arbitrary
expression. `zscore(gold.close / silver.close, 100) >= 2.0` parses, compiles, and evaluates
deterministically **today**. Section 7 documents exactly what exists, what the one real gap
is (it must reference a *registered stream alias* — which a BASKET alias is), and what we
must add (almost nothing but a test and docs). **The real work in #457 is BASKET.**

This reframes the sequencing the issue assumed: zscore does not need to "land first" — it
is done. BASKET is the heavy lift and lands independently; zscore is verified to compose
with it in the same plan.

## 2. What the engine already gives us (grounding)

The design rests on five existing mechanisms. Each is cited so the plan can extend rather
than reinvent.

| Mechanism | File / type | What it does for us |
|---|---|---|
| Stream → identity | `dsl/ast/StreamDecl`, `dsl/compile/HubKey` | An alias maps to `HubKey(broker, symbol, timeframe)`; `qktSymbol = "$broker:$symbol"`. The compiler builds `streams: Map<String, HubKey>` (`AstCompiler.compile`, line 32). |
| Price read | `ExprCompiler.compileCandleField` (line 543) | `<alias>.close` reads `ctx.streams[alias]` then the current candle or `ctx.hub.latest(key)`. **A basket alias needs only a HubKey entry + a candle in the hub** to be readable — no new read path. |
| Candle backplane | `dsl/compile/CandleHub` | Per-`HubKey` ring of closed candles; `latest`, `history`, `onClosed`, `seed`. Strategies subscribe per stream. |
| Bar alignment | `CandleHub.routeToSyncSlots` + `SyncGroupKey` (#45) | Holds each member's closed bar by `endTime`; fires once when all members of a group have the same window. **This is exactly the alignment a composite needs** — all constituents' same-window bars present before computing. |
| Rolling stats | `IndicatorBinding` (expression-fed, #174), `ZScore`, `Stddev` | Arbitrary-expression series feed into a ring-buffered indicator; warmup + `Value.Undefined`-until-ready handled by the framework. |

Order/position facts the fan-out builds on:

- A `Buy`/`Sell`/`Close` action carries **one** `stream` alias
  (`dsl/ast/RuleAst.kt`); `ActionCompiler.compileBuySell` resolves it to a single
  `qktSymbol` and emits one `Signal.Buy/Sell/Submit`.
- Multi-order-from-one-action precedent exists but is always **single-symbol**: a
  `Bracket` decomposes to entry+TP+SL (3 orders), a `Stack`/`STACK_AT` fires N layered
  orders. The fan-out across **N symbols** is genuinely new.
- Positions are tracked per `(strategyId, symbol)` as a `LegBook` of `PositionLeg`s with
  roles `PRIMARY | STACK | INDEPENDENT` (`positions/StrategyPositionTracker`,
  `positions/PositionLeg`). `CLOSE <alias>` resolves the alias to one symbol and closes
  that symbol's legs (`ActionCompiler.closeSignalsFor`, close-by-ticket for INDEPENDENT
  legs).
- Routing is by symbol pattern: `broker/CompositeBroker` and
  `marketdata/source/CompositeMarketSource` both match `qktSymbol` prefixes to a leaf.
- **MACRO is the precedent for a read-only derived stream** (`marketdata/source/MacroMarketSource`,
  `MACRO:` prefix) plus the **compile-time order-rejection** pattern
  (`AstCompiler.rejectMacroOrders`, line 50). BASKET is the mirror image: derived *and*
  tradeable (fans out instead of being rejected).

## 3. The BASKET abstraction — three candidate shapes

The central decision: what *is* a basket?

### 3a. Candidate A — pure eval-time macro (no stream, no hub entry)

`BASKET ...` desugars to an inline expression; `antipodean.close` rewrites to
`(aud.close + nzd.close) / 2` wherever it appears.

- **Pros:** smallest parser change; no new hub/candle machinery.
- **Cons, fatal:** (1) `BUY antipodean` has no symbol to route — fan-out has to be a
  parallel special case bolted onto every action. (2) `zscore(gold.close /
  antipodean.close, 100)` would inline a multi-stream expression with **no single primary
  alias** for the expression-fed gate — the basket would have to pick one constituent's bar
  to gate on, and a constituent's bar can close while the other's is stale, so the composite
  the z-score samples is **mis-aligned bar-to-bar**. Determinism and the measured edge both
  die here. (3) No `POSITION.antipodean`, no `history`, no warmup as a unit. Rejected.

### 3b. Candidate B — synthetic *tradeable* stream (recommended)

A basket is a **first-class stream** with its own identity
`HubKey(broker = "BASKET", symbol = <name>, timeframe = <tf>)`, registered in the
`streams` map alongside real streams. Its composite candle is **computed by an implicit
sync-group over its constituents and written into the CandleHub** under the basket's key.
Everything downstream — `antipodean.close`, `zscore(... antipodean.close ...)`,
`POSITION.antipodean`, warmup, lookback — reuses the existing per-stream paths unchanged.
The *only* two new behaviours are (i) **composite-candle computation** on the constituent
sync-fire, and (ii) **order fan-out** when an action targets a basket alias.

- **Pros:** composes with every existing primitive by construction (read path, z-score
  gate, position model, warmup, sync). The composite is computed **once per aligned
  window**, so it is deterministic and identical in backtest and live (both drive the same
  `CandleHub`). Mirrors the MACRO precedent for "a stream that isn't a raw venue feed."
- **Cons:** requires the composite-candle writer and a new fan-out action path; the
  basket's bars are a derived artifact in the hub (small memory, bounded by retention).
- **This is the choice.** Rationale in §0-style terms: complexity stays **local to two new
  compiler components**; the consumer learns one new primitive (`BASKET`) and everything
  else they already know keeps working.

### 3c. Candidate C — synthetic market *source* (`BASKET:` feed, like MACRO)

A `BasketMarketSource` synthesizes composite ticks under a `BASKET:` prefix, merged via
`MergingTickFeed`, aggregated to candles by the normal path.

- **Pros:** maximal reuse of the source/aggregator stack; reads identically to any symbol.
- **Cons:** the basket definition (constituents + weights) is **strategy-level DSL**, not a
  data-catalog fact like a FRED series — pushing it into a market source means the source
  must be parameterized per strategy and the composite must be re-derived from raw ticks
  rather than from the **aligned closed bars** the strategy already shares. Tick-level
  compositing also reintroduces the alignment problem (constituent ticks interleave); we'd
  rebuild sync at the tick layer. Worse parity story than B, where the composite is a pure
  function of the same closed bars in both modes. Rejected in favour of B, but noted as the
  fallback if we ever want baskets usable **outside** a strategy (e.g. as a backtest data
  series).

**Decision: Candidate B.** A basket is a synthetic tradeable stream; its candle is computed
at constituent-sync-fire time and written to the `CandleHub`.

## 4. Composite price semantics — resolving the contradiction

The issue is internally inconsistent: the proposed-solution text says "`antipodean.close`
is the **price-weighted average**" while the research quote says "equal-weight **log-return**
composite." These are different objects. We must pick one and state it precisely, because
the z-score of the spread is only meaningful if the composite is well-defined.

### 4a. Why a raw price average is wrong

AUDUSD ≈ 0.66 and NZDUSD ≈ 0.60 are quoted in the same units (USD per unit of AUD/NZD), so
`(aud + nzd)/2` is not dimensionally absurd — but it is still the wrong factor:

- It is **dominated by levels, not co-movement.** A basket meant to "isolate the common
  commodity factor and denoise country-specific shocks" must weight the two currencies'
  *returns* equally. A price average weights them by their price level, so a higher-priced
  constituent silently carries more of the basket. For AUD/NZD the levels are close so the
  distortion is small — but the abstraction must be correct for the general case (a basket
  of e.g. XAUUSD ≈ 2300 and EURUSD ≈ 1.08 under a price average is ~99.95% gold).
- The **ratio** `gold.close / basket.close` and its z-score inherit those arbitrary level
  weights, so the "extreme" the 2-sigma trigger selects is not the structural extreme the
  research measured.

### 4b. Chosen definition — equal-weight return index (geometric, rebased)

Define the basket as an **index** that starts at a base value on its first fully-aligned
bar and compounds the **equal-weighted average of constituent log-returns** thereafter. For
constituents `c1..cN` with closes `p_i(t)`:

```
r_i(t)   = ln( p_i(t) / p_i(t-1) )            per-constituent log return
R(t)     = (1/N) * Σ_i r_i(t)                  equal-weight average return
I(t)     = I(t-1) * exp( R(t) ),   I(t0) = BASE (default 100)
```

`antipodean.close = I(t)`. The basket's synthetic candle carries this index value as its
`close` (and `open`/`high`/`low` derived from the index — see §5c). Properties that make it
the right factor:

- **Equal-weight in returns** by construction — the stated goal. Pooling AUD and NZD returns
  denoises RBA/RBNZ country shocks and isolates the shared commodity/risk factor.
- **Scale-invariant / dimensionless dynamics.** The *level* of `I` is arbitrary (the BASE),
  but `gold.close / I` is a ratio whose **z-score is invariant to BASE** (z-score subtracts
  the mean and divides by stddev, both of which scale with BASE). So the entry/exit
  thresholds are stable regardless of base or constituent price levels.
- **Backtest=live identical:** `I(t)` is a pure recurrence over the same aligned closed
  bars; no path dependence beyond the previous index value, which both modes reproduce
  bit-for-bit (BigDecimal `Money.CONTEXT`, the engine's standard).

### 4c. Rejected alternative — arithmetic mean of returns into a price

`I(t) = I(t-1) * (1 + R(t))` (simple-return compounding) is nearly identical for small bars
and avoids `ln`/`exp`. We choose **log** because it is symmetric (a +1% then −1% returns to
base) and is the standard for return composites; the cost is two transcendental calls per
bar per constituent, negligible at realistic periods. `FuncRegistry` already exposes `LOG`
and `EXP` over `Money.CONTEXT`, so the numeric convention is settled and reused.

### 4d. The first-bar problem

`r_i(t)` needs `p_i(t-1)`. The index is therefore **Undefined until each constituent has at
least two aligned bars** (its first defined `close` is `BASE` on the first aligned bar, with
`R` taken as 0; the first *return* lands on the second aligned bar). This dovetails with the
existing warmup/`Value.Undefined` contract: rules referencing `antipodean.close` simply skip
until the basket is warm, like any indicator.

## 5. BASKET mechanics

### 5a. DSL surface and grammar

Declared in the `SYMBOLS` block, after the real streams it references:

```
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 1h
    aud  = EXNESS:AUDUSD EVERY 1h
    nzd  = EXNESS:NZDUSD EVERY 1h
    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
```

Grammar for a basket declaration line:

```
<alias> = BASKET EQUAL_WEIGHT '[' <alias> (',' <alias>)+ ']' EVERY <timeframe>
```

- `BASKET` and `EQUAL_WEIGHT` are **new keywords**. The lexer derives keywords from
  `TokenKind.values()` by name (`Lexer.KEYWORDS`, line 254), so adding two `TokenKind`
  entries registers them automatically — no map edit. `[`, `]`, `,` already lex
  (`LBRACKET`, `RBRACKET`, `COMMA`).
- `EQUAL_WEIGHT` is the only weighting mode in scope; making it an explicit token (not an
  implicit default) reserves grammar space for `WEIGHTED [...]` /
  `BETA_WEIGHTED [...]` later (§8) without a breaking change.
- The `EVERY <timeframe>` must match the constituents' timeframe (validated, §6). A basket
  has no broker/symbol of its own in the source text; the compiler assigns identity
  `HubKey("BASKET", <alias-uppercased>, <tf>)`.

New AST node (`dsl/ast/StrategyAst.kt`, alongside `StreamDecl`):

```kotlin
data class BasketDecl(
    val alias: String,
    val weighting: BasketWeighting,      // sealed: EqualWeight (only member for now)
    val constituents: List<String>,      // alias references, >= 2, unique
    val timeframe: String,
) { /* require: >=2 constituents, distinct, alias not blank */ }
```

`StrategyAst` gains `val baskets: List<BasketDecl> = emptyList()`. `SymbolsBlock` carries
them out of the parser.

### 5b. Identity, registration, and the constituent sync-group

In `AstCompiler.compile`:

1. Build `streams` for real `StreamDecl`s as today.
2. For each `BasketDecl`, add `alias -> HubKey("BASKET", alias.uppercase(), tf)` to
   `streams`. From here, `antipodean.close` resolves through `compileCandleField`
   **unchanged**, reading the composite candle from the hub.
3. Each basket induces an **implicit sync-group** over its constituents (reusing
   `SyncGroupKey`). The constituents must already be in a shared bar window for the
   composite to be correct, which is precisely what the sync machinery guarantees.
4. Register the basket's own `HubKey` in `retentionByKey` (retention = the strategy's max
   lookback, same rule as real streams) so its ring holds enough history for
   `antipodean.close[N]` and for z-score lookback.

### 5c. Composite-candle computation (the one new write path)

A new compiler component — call it the **BasketCompositor** — owns one rolling index state
per basket (previous index value `I(t-1)`, and previous aligned close per constituent
`p_i(t-1)`). On the basket's constituent-sync fire (delivered through `CandleHub.onSyncClosed`,
which already hands over `Map<alias, Candle>` for the same `endTime`):

1. Read each constituent's closed bar from the delivered map.
2. Compute `R(t)` and `I(t)` per §4b (BigDecimal, `Money.CONTEXT`, `LOG`/`EXP`).
3. Synthesize a `Candle` for the basket key:
   - `close = I(t)`, `startTime/endTime` = the shared window.
   - `open` = `I(t-1)` (the index entering the bar); `high`/`low` = `max/min(open, close)`.
     This is a faithful index OHLC for a bar; it keeps `.open/.high/.low` meaningful for any
     indicator a strategy points at the basket, without inventing intrabar path.
   - `volume = ZERO` (a synthetic index has no traded volume; volume-weighted indicators on
     a basket are rejected the same way MACRO rejects them, §6).
4. **Write it into the hub** for the basket key so the per-stream read path and any
   basket-anchored rules/indicators see it. The basket then fires its own rules and updates
   its own indicator bindings on this synthesized close, via the same two-pass
   (update-then-fire) the sync path already uses for aligned aliases (`AstCompiler`
   `bindToHub`, lines 386-403).

Determinism: `I(t)` depends only on `I(t-1)` and the two aligned closes — a pure recurrence
over identical inputs in backtest and live.

Hot-path cost: O(N) per aligned window per basket (N = constituents), plus the existing
sync bookkeeping. No per-tick work beyond what the constituents already incur.

### 5d. Order fan-out — `BUY` / `SELL` antipodean

When a `Buy`/`Sell` action's `stream` resolves to a **basket** alias, `ActionCompiler`
takes a new fan-out path instead of emitting a single order:

- Emit **one order per constituent**, each on the constituent's real `qktSymbol`, routed by
  `CompositeBroker` exactly as a direct order would be.
- **Side:** `BUY antipodean` → BUY every constituent; `SELL antipodean` → SELL every
  constituent (the market-neutral pairing of long-basket / short-gold is expressed by the
  strategy issuing `SELL gold` + `BUY antipodean` on the same trigger; the basket itself is
  uniform-side).
- **Per-constituent size — equal notional (recommended).** "Equal-weight" must mean equal
  *economic* weight, not equal lots: 1 lot of AUDUSD and 1 lot of NZDUSD are different
  notionals and different USD betas. Each constituent's quantity is derived so its notional
  equals `total / N`, where `total` is the action's sizing resolved once. Concretely, reuse
  `SizingCompiler` to resolve the basket order's target notional, then per constituent
  `qty_i = (notional / N) / (price_i * contractSize_i)` — the same formula `SizeNotional`
  already uses (`SizingCompiler`, contract size via `instruments.require(qktSymbol)`).
  Equal-lot is offered as an explicit opt-out only if a later need appears (YAGNI: not now).
- **Brackets/TIF/stack on a basket order:** out of scope for v1 and a **compile error**
  (a per-leg bracket on a fanned-out order is a different feature; see §8). The basket
  order is plain market in v1; exits are via `CLOSE antipodean` and rule-driven z-score
  conditions, which is exactly what the motivating strategy needs.

### 5e. Position model — `POSITION.antipodean` and `CLOSE antipodean`

The fan-out produces N real legs, one per constituent symbol, in the existing
`StrategyPositionTracker` (keyed `(strategyId, constituentSymbol)`). The basket is a
*view* over them, not a new storage shape:

- `POSITION.antipodean` (the `PositionRef` path, `ExprCompiler.compilePositionRef`) is
  extended: when the alias is a basket, return a **unit-normalized aggregate** — `+1` if all
  constituent legs are long, `-1` if all short, `0` if flat. (Returning a summed quantity
  is meaningless across different symbols/contract sizes; the position-gate idiom the
  strategy uses is `POSITION.antipodean = 0`, for which the unit form is exactly right.)
  This mirrors how `POSITION.gold = 0` is used as an "am I flat here" gate in the existing
  hedge-straddle example.
- `CLOSE antipodean` (the `Close` action) fans out: for each constituent, emit the same
  close signals `closeSignalsFor` already produces for that symbol (close-by-ticket for
  INDEPENDENT legs, net-close otherwise). One basket close → N constituent closes.
- **Leg tagging.** Constituent legs opened by a basket order carry the basket alias as
  metadata (a nullable `basketAlias` on `PositionLeg`, defaulting null) so (a) the
  unit-normalized `POSITION.antipodean` reads only basket legs and (b) a future per-basket
  reconciler can group them. This is the smallest field that makes the view exact; it does
  not change the leg's storage key.

### 5f. Backtest=live determinism

Both modes drive the same `CandleHub` from the same closed bars (backtest via the
deterministic `MergingTickFeed` k-way merge; live via the aggregator). The composite is a
pure function of those bars. Therefore the basket's candle series, its z-scores, its fan-out
order quantities (given the same prices and instrument metadata), and its closes are
identical across modes — the engine's core invariant holds with no special-casing.

## 6. Validation and error handling

Enforced at compile time (parser or `AstCompiler`), mirroring existing validation density:

- **Unbound constituent:** every alias in `[...]` must be a declared real stream. Error
  naming the offending alias (cf. `SYNCHRONIZE` alias validation, `Parser` line 1648).
- **Fewer than two constituents:** rejected in `BasketDecl.init` (a one-symbol "basket" is
  just that symbol).
- **Basket of baskets:** a constituent that is itself a basket is rejected in v1 (the index
  recurrence assumes raw-price constituents; nesting is unmotivated). Explicit error.
- **Timeframe mismatch:** the basket's `EVERY <tf>` must equal every constituent's
  timeframe — `SyncGroupKey.init` already requires members share a timeframe; surface a
  clear basket-specific message before that generic check fires.
- **Mixing brokers across constituents:** *allowed* (the fan-out routes each by its own
  `qktSymbol`; a cross-venue basket is legitimate — e.g. a metals basket on one broker).
  But the **sync timeout** matters: cross-venue bars may not align tick-for-tick. v1 uses
  the constituents' shared timeframe window with no extra timeout (wait-for-all), matching
  default `SYNCHRONIZE` semantics; document that a constituent that stops printing stalls
  the basket (same failure mode as `SYNCHRONIZE`).
- **Ordering a basket with brackets/TIF/stack:** compile error in v1 (§5d).
- **Volume-weighted indicator on a basket** (`VWAP`/`OBV` pointed at `antipodean`):
  rejected — the synthetic candle has zero volume (cf. `requiresVolume` checks,
  `IndicatorBinding`).
- **`zscore` referencing only baskets:** fine — a basket alias is a registered stream, so
  `streamAliasesIn` finds it and the primary-alias gate is satisfied (§7).

## 7. `zscore(expr, lookback)` — what exists, the gap, what we add

### 7a. What already ships

- `ZScore` indicator: rolling `(latest − mean)/stddev`, **sample (n−1) stddev** matching
  `Stddev`, returns null until `period` samples and null on a flat window (zero stddev),
  all in `Money.CONTEXT` with `Money.SCALE`/`HALF_EVEN`. Warmup = `period`.
- Registered as `ZSCORE`, arity 2 (`IndicatorRegistry`).
- **Expression-fed binding** (`IndicatorBinding.bindExpression`, routed from
  `ExprCompiler.compileIndicator`, line 469): when the first arg is neither a
  `StreamFieldRef` nor an `IndicatorCall`, the whole expression is compiled to a
  `CompiledExpr` and fed to the indicator each bar, gated on the **primary alias** = the
  first stream the expression references (`streamAliasesIn`). So
  `zscore(gold.close / silver.close, 100)` already works and is unit-tested in spirit by
  `ExpressionFedIndicatorTest` (`stddev(a.close - 75*b.close, 60)`).

Determinism, warmup, and `Value.Undefined` propagation (incl. Kleene `AND`/`OR`) are all
framework services the z-score inherits unchanged.

### 7b. The one real gap, and why BASKET closes it

The expression-fed gate requires the series to reference **at least one registered stream
alias** (else `streamAliasesIn` returns empty and compilation errors). For the motivating
condition `zscore(gold.close / antipodean.close, 100)`, `antipodean` **must be a registered
stream alias** for the gate to bind — which is exactly what the BASKET design makes it
(§5b). So the gate behaviour we want falls out for free: the z-score binds, with `gold` as
primary alias (first referenced), and updates once per aligned `gold` bar. **No change to
the z-score machinery is required for the motivating strategy.**

One subtlety to verify in the plan: the expression-fed binding gates updates on the
**primary alias's** bar close. When the expression mixes `gold` and a basket at the same
timeframe, the gate fires on `gold`'s close and reads the basket's latest hub candle. For
the composite to be the **same-window** value (not the prior window), `gold` should be in
the basket's alignment set — i.e. the strategy should `SYNCHRONIZE gold antipodean` (or the
basket's constituents and gold share a sync group). This is the same cross-stream alignment
requirement that already applies to `sma(silver.close, …)` on a gold-anchored rule; the
plan adds a doc note and a test, not new code.

### 7c. What we actually add for zscore

Effectively nothing functional — but to *close* #457's zscore half properly:

- A focused **end-to-end test**: a ratio-z-score strategy on two real streams and on a
  `gold.close / basket.close` ratio, asserting entries fire only past ±2 and that
  backtest is deterministic across two runs.
- **Docs**: a `zscore` entry in `docs/reference/dsl/indicators.md` with the pairs-spread
  worked example, since today it is registered but under-documented.

This is why zscore "ships first": it is already shipped; the plan's zscore tasks are
verification + documentation and can land before any BASKET code.

## 8. Out of scope (named follow-ups)

- **Beta-weighted / market-neutral sizing.** The research wants the basket sized to
  *neutralize net gold/USD beta* (a rolling `BETA`/`CORRELATION` between gold and the basket
  drives constituent weights). `BETA` already exists as a two-series indicator. This is a
  real, motivated next step but is **its own feature**: it needs a `BETA_WEIGHTED [...]`
  weighting mode and beta-driven fan-out sizing. v1 ships equal-weight + equal-notional;
  beta-weighting is **P2** and the grammar (`EQUAL_WEIGHT` as an explicit token) is reserved
  for it.
- **Per-leg brackets/stacks on a basket order.** Fanning a bracket across N legs (each with
  its own SL/TP) is a distinct execution feature; v1 forbids it (§5d).
- **Baskets as standalone backtest data series** (usable outside a strategy) — that would be
  Candidate C (a `BASKET:` market source); not needed for #457.
- **Half-life / cointegration estimate** the research mentions — a separate analytics
  capability, not part of these two primitives.

## 9. Testing strategy

Following project standards (real types, no mocks, JUnit5+AssertJ, deterministic, exact
asserts):

- **Parser:** `BASKET EQUAL_WEIGHT [a, b] EVERY 1h` parses to a `BasketDecl`; rejects
  one-constituent, duplicate-constituent, missing-bracket, and unknown-constituent forms
  with clear errors.
- **Compositor (unit):** feed known constituent closes across several aligned windows;
  assert the index recurrence to exact BigDecimal values (e.g. two constituents with +1% /
  −1% returns return the index to ~BASE); assert `Undefined` before the second aligned bar;
  assert flat input behaviour.
- **Compile/eval:** `antipodean.close` reads the synthesized hub candle; `POSITION.antipodean`
  returns ±1/0 correctly across fanned legs; `zscore(gold.close/antipodean.close, n)` binds
  with `gold` primary and yields the expected scalar on a hand-built series.
- **Fan-out (eval):** `BUY antipodean SIZING NOTIONAL X` emits N orders whose per-constituent
  notionals equal `X/N` (assert quantities against the `SizeNotional` formula with fixed
  instrument metadata); `CLOSE antipodean` emits the matching N closes.
- **End-to-end backtest:** the full XAU-vs-antipodean mean-reversion strategy runs on real
  stored bars, produces trades, and is **bit-identical across two runs** (parity invariant).
  No mocks; real `MergingTickFeed` data path.
- **Validation:** every §6 rejection has a test asserting the specific error message.

## 10. Open questions / decisions

Each fork below carries a recommendation; flagged items need elitekaycy's call before build.

1. **Composite definition — return index vs price average.** *Recommendation: equal-weight
   log-return index (§4b).* It is the only definition that makes "equal-weight" mean equal
   economic co-movement and keeps the ratio z-score scale-invariant. **This is the one
   decision I'd most want confirmed** — it contradicts the issue's "price-weighted average"
   wording, and the whole edge depends on getting the factor right. *(needs confirmation)*
2. **Basket identity broker tag.** *Recommendation: `broker = "BASKET"`, `symbol =
   alias.uppercased()`.* Keeps `qktSymbol` unique and human-readable (`BASKET:ANTIPODEAN`),
   and lets `CompositeBroker`/`CompositeMarketSource` ignore it (baskets never route as a
   symbol — only their constituents do). *(safe default)*
3. **Fan-out sizing — equal notional vs equal lots.** *Recommendation: equal notional
   (§5d).* Equal lots is not equal-weight in any economic sense. *(recommend; confirm)*
4. **`POSITION.antipodean` semantics.** *Recommendation: unit-normalized ±1/0 (§5e),* since
   a cross-symbol quantity sum is meaningless and the only real use is the flat-gate idiom.
   Alternative (return min |qty| or per-leg detail) is YAGNI. *(recommend)*
5. **Composite OHLC shape.** *Recommendation: `open=I(t-1)`, `close=I(t)`, `high/low` =
   max/min of the two, `volume=0` (§5c).* Faithful index bar without inventing intrabar
   path. *(safe default)*
6. **Gold-vs-basket alignment for the z-score.** *Recommendation: require/encourage the
   strategy to `SYNCHRONIZE` gold with the basket (doc + test, no new code, §7b).* Forcing
   an implicit gold-into-basket sync group is possible but over-reaches what the user wrote;
   keep it explicit. *(recommend; revisit if footguns appear)*
7. **Cross-broker basket sync timeout.** *Recommendation: wait-for-all (no timeout) in v1,
   documented stall behaviour (§6).* A configurable `WITHIN` on the basket is a trivial
   later add if needed. *(safe default)*
8. **Sequencing.** *Recommendation: ship zscore docs+test first (it already works), then
   BASKET.* The plan is ordered this way. *(no blocker)*

## 11. References

- Issue: #457.
- Plan: `docs/superpowers/plans/2026-06-14-basket-zscore.md`.
- Grounding code: `dsl/compile/AstCompiler.kt`, `dsl/compile/ExprCompiler.kt`
  (`compileCandleField`, `compileIndicator`, `compilePositionRef`),
  `dsl/compile/CandleHub.kt`, `dsl/compile/SyncGroupKey.kt`,
  `dsl/compile/IndicatorBinding.kt`, `indicators/catalog/ZScore.kt`,
  `indicators/catalog/Stddev.kt`, `dsl/stdlib/IndicatorRegistry.kt`,
  `dsl/compile/ActionCompiler.kt`, `dsl/compile/SizingCompiler.kt`,
  `positions/StrategyPositionTracker.kt`, `positions/PositionLeg.kt`,
  `dsl/parse/Parser.kt` (`parseSymbols`), `dsl/parse/Lexer.kt` (`KEYWORDS`),
  `dsl/parse/TokenKind.kt`.
- Precedents: MACRO read-only derived stream
  (`marketdata/source/MacroMarketSource.kt`, `AstCompiler.rejectMacroOrders`); bar-sync
  (`docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md`, #45).
