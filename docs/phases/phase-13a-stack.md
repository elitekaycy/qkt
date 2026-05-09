# Phase 13a — STACK: Pyramiding / Scaling-In Order Modifier

## Summary

Phase 13a adds a `STACK` keyword to both the external DSL parser and the internal Kotlin DSL that collapses an entire scaling-in plan into a single `BUY` or `SELL` action. Where strategies previously needed explicit state machines built from multiple WHEN/THEN rules to pyramid into a trend, `STACK` expresses that intent in one line: "enter N times at price increments, cancel leftovers if the trend stalls." Brokers see only ordinary Market/Stop/Limit orders; the engine handles sequencing, anchor capture, and per-stack lifecycle internally via a new `StackTracker` owned by `OrderManager`.

## What's new

### DSL keywords (external parser)

- `STACK` — introduces a scaling-in plan inside a `BUY` or `SELL` action.
- `SPACING` — compact form: `STACK <n> SPACING <distance>`. Generates N layers spaced `<distance>` apart.
- `WITHIN` — time fence: `WITHIN <duration>`. Cancels unfired pending layers after the deadline.
- `ABOVE` / `BELOW` — explicit direction override on the spacing form (already existed as tokens; now carry stack semantics).
- `DURATION` literal — e.g., `1h`, `30m`, `15s`, `2d`. Parsed greedily; no whitespace between number and unit.
- `entry` magic identifier — valid inside layer-list `AT <expr>` clauses; resolves to layer 1's actual fill price at runtime.

### AST (`com.qkt.dsl.ast`)

- `StackAst` — sealed interface; two implementations below.
- `StackSpacing` — compact form: `count`, `spacing: ExprAst`, `direction: StackDirection`, `within: DurationAst?`.
- `StackLayers` — explicit form: `layers: List<StackLayer>`, `within: DurationAst?`.
- `StackDirection` — enum: `TRADE_DIRECTION`, `ABOVE`, `BELOW`.
- `StackLayer` — one entry in a layer list: `sizing: SizingAst`, `orderType: OrderTypeAst?`, `at: ExprAst?`.
- `DurationAst` — wraps a millisecond count; validated `> 0` at construction.
- `StackEntryRef` — singleton `ExprAst` node; the magic `entry` identifier in AST form.
- `ActionOpts.stack: StackAst?` — new optional field on the existing action-options carrier.

### Runtime IR (`com.qkt.execution`)

- `StackPlan` — flattened list of `LayerSpec` plus optional `outerBracket: BracketAst?` and `withinMillis: Long?`.
- `LayerSpec` — one layer: `index: Int`, `sizing: SizingAst`, `orderType: OrderTypeAst`, `trigger: LayerTrigger`, `resolvedQuantity: BigDecimal?`.
- `LayerTrigger` — sealed interface: `Immediate` (layer 1) or `At(price: ExprAst, direction: StackDirection)`.
- `OrderRequest.Stack` — new variant of the existing `OrderRequest` sealed interface carrying the `StackPlan`.

### Kotlin DSL helpers (`com.qkt.dsl.kotlin`)

- `stack(count, spacing, direction, within)` — builds `StackSpacing`.
- `stackOf(vararg layers, within)` — builds `StackLayers`; validates layers 2..N have `at` clauses.
- `layer(qty: ExprAst, orderType?, at?)` — builds `StackLayer` from a quantity expression.
- `layer(sizing: SizingAst, orderType?, at?)` — builds `StackLayer` from any sizing form.
- `entryPrice` — top-level `val` equal to `StackEntryRef`; use in `at` expressions.
- `duration(text: String)` — parses `"1h"` / `"30m"` / `"15s"` / `"2d"` into `DurationAst`.

### Compiler (`com.qkt.dsl.compile`)

- `StackCompiler` — folds a `StackAst` into a `StackPlan`. `StackSpacing` becomes N `LayerSpec`s with `At(entry + spacing * i, direction)` triggers; `StackLayers` is preserved as-is with index assignment.

### Runtime (`com.qkt.app`)

- `StackTracker` — internal class owned by `OrderManager`. Tracks per-stack state: anchor price, deadline epoch, layer-1 order id, pending/filled/closed layer id sets. Exposes `register`, `setAnchor`, `addPending`, `markFilled`, `markLayerClosed`, `stackOwning`, `terminate`, `all`.
- `OrderManager.submitStack` — dispatches `OrderRequest.Stack`: submits layer 1 immediately, registers the stack in `StackTracker`, materializes pending layers on layer-1 fill, evaluates WITHIN deadline each tick, cancels pending layers when the position is flat.
- `OrderManager.pendingStackLayerInfos()` — returns a snapshot list of `PendingStackLayerInfo` DTOs for all currently-pending stack layers.

### Observability

- `PendingStackLayerInfo` DTO — `stackId`, `layer`, `triggerPrice`, `side`, `quantity`. Nested in `OrderManager`.
- `StatusSnapshot` — the 12b observability HTTP `/status` response now includes `pendingStackLayers: List<PendingStackLayerInfo>` per strategy.

## Migration from previous phase

No breaking changes. All additions are opt-in:

- `ActionOpts` gains a nullable `stack` field (default `null`); existing callers compile unchanged.
- `OrderRequest.Stack` is a new sealed-interface variant; existing `when` exhaustive branches are unaffected because the final `else` clause in `OrderManager.dispatch` remains.
- `LiveSession` is unchanged.
- No renames, no removed APIs.

## Usage cookbook

### 1. Simple pyramid — SPACING form

The most common use: buy N times as price climbs, with a shared bracket per layer.

```
STRATEGY btc_pyramid VERSION 1
SYMBOLS
    btc = BYBIT:BTCUSDT EVERY 1m
RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1
         STACK 3 SPACING 100
         BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

Layer 1 fires at market on the EMA cross. Assume fill at 50000 (the anchor). Layers 2 and 3 become pending Stop orders at 50100 and 50200. Each filled layer inherits its own Stop-Loss at `fill - 50` and Take-Profit at `fill + 200`. If layer 1's SL fires at 49950 before 50100 is reached, layers 2 and 3 cancel automatically. Full exposure at three layers: 0.3 BTC.

Kotlin DSL equivalent:

```kotlin
buy(btc, sizing = sizeQty(num("0.1"))) {
    stack = stack(count = 3, spacing = num("100"))
    bracket = bracket(sl = byDistance(num("50")), tp = byDistance(num("200")))
}
```

### 2. Average-down with `BELOW`

Enter more as price drops, anticipating a bounce.

```
WHEN rsi(btc.close, 14) < 30
THEN BUY btc SIZING 0.1
     STACK 3 SPACING 100 BELOW
     BRACKET { STOP LOSS BY 200, TAKE PROFIT BY 300 }
```

`BELOW` overrides the default (with-trend = upward for BUY). Layers 2 and 3 trigger at `anchor - 100` and `anchor - 200`. Combined exposure: 0.3 BTC averaging down.

Kotlin DSL:

```kotlin
buy(btc, sizing = sizeQty(num("0.1"))) {
    stack = stack(count = 3, spacing = num("100"), direction = StackDirection.BELOW)
    bracket = bracket(sl = byDistance(num("200")), tp = byDistance(num("300")))
}
```

### 3. Layer-list with explicit per-layer prices and order types

Full control: each layer specifies its own sizing, order type, and trigger relative to `entry`.

```
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc STACK [
       0.1,                             -- layer 1: market at signal
       0.2 AT entry + 100,             -- layer 2: market triggered at +100
       0.3 LIMIT AT entry + 200,       -- layer 3: limit at +200
     ]
     BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

`entry` is the magic identifier for layer 1's fill price. Layer 3 uses `LIMIT` so the order becomes a resting limit rather than a stop-market. Note: no outer `SIZING` clause when using the layer-list form — sizing lives inside each layer.

Kotlin DSL:

```kotlin
buy(btc) {
    stack = stackOf(
        layer(qty = num("0.1")),
        layer(qty = num("0.2"), at = entryPrice + num("100")),
        layer(qty = num("0.3"), orderType = Limit, at = entryPrice + num("200")),
    )
    bracket = bracket(sl = byDistance(num("50")), tp = byDistance(num("200")))
}
```

### 4. Mixed sizing in a layer list

Each layer can use a different sizing form. Sizing is evaluated when that layer fires, not when the stack is submitted.

```
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc STACK [
       0.1 AT entry,                   -- fixed qty
       RISK 0.01 AT entry + 100,       -- 1% fractional risk
       5000 USD AT entry + 200,        -- notional
       2 % OF EQUITY AT entry + 300,   -- % of equity at fire time
     ]
     BRACKET { STOP LOSS BY 50 }
```

Kotlin DSL:

```kotlin
buy(btc) {
    stack = stackOf(
        layer(sizing = sizeQty(num("0.1")), at = entryPrice),
        layer(sizing = sizeRiskFrac(num("0.01")), at = entryPrice + num("100")),
        layer(sizing = sizeNotional(num("5000")), at = entryPrice + num("200")),
        layer(sizing = sizeEquityPct(num("2")), at = entryPrice + num("300")),
    )
    bracket = bracket(sl = byDistance(num("50")))
}
```

### 5. WITHIN time fence

Cancel pending layers if price never reaches them within a time window.

```
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc SIZING 0.1
     STACK 3 SPACING 100 WITHIN 1h
     BRACKET { STOP LOSS BY 50 }
```

Layer 1 fills. If layers 2 and 3 have not triggered within 1 hour of layer 1's fill, both are cancelled. Layer 1's bracket continues to live its lifecycle normally — WITHIN only cancels unfired pending layers.

Kotlin DSL:

```kotlin
buy(btc, sizing = sizeQty(num("0.1"))) {
    stack = stack(count = 3, spacing = num("100"), within = duration("1h"))
    bracket = bracket(sl = byDistance(num("50")))
}
```

Duration units: `s` (seconds), `m` (minutes), `h` (hours), `d` (days).

### 6. SELL with STACK

STACK works symmetrically on the short side. Default direction for SELL is downward (with-trend = price falling).

```
WHEN rsi(btc.close, 14) > 70
THEN SELL btc SIZING 0.1
     STACK 3 SPACING 100
     BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

Layer 1 sells at market. Layers 2 and 3 trigger at `anchor - 100` and `anchor - 200` (price falling). Each layer's SL is at `fill + 50`; TP is at `fill - 200`. Use `ABOVE` to flip to short average-up semantics.

### 7. FOR/EACH composition

STACK composes naturally with the FOR/EACH iteration construct; each symbol gets its own independent stack.

```
RULES
    FOR EACH s IN [btc, eth, sol] DO
        WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
        THEN BUY s SIZING 0.1
             STACK 3 SPACING 100
             BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

Each symbol generates its own rule. Stacks for BTC, ETH, and SOL are tracked independently in `StackTracker`. One symbol's layer-cancel event does not affect another's pending layers.

## Testing patterns

### Synthetic-tick backtest approach

The canonical test pattern for stack strategies. Build the `StackPlan` by hand (or via DSL), construct a one-shot `Strategy` that emits the `OrderRequest.Stack` signal on the first qualifying tick, then feed a controlled tick stream to `Backtest`.

```kotlin
@Test
fun `pyramid happy path fills three layers at entry plus spacing`() {
    val plan = StackPlan(
        layers = listOf(
            LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
            LayerSpec(
                2, SizeQty(NumLit(BigDecimal("0.1"))), Market,
                At(BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                   StackDirection.TRADE_DIRECTION),
            ),
            LayerSpec(
                3, SizeQty(NumLit(BigDecimal("0.1"))), Market,
                At(BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("200"))),
                   StackDirection.TRADE_DIRECTION),
            ),
        ),
    )

    val ticks = listOf(
        Tick("btcusdt", Money.of("49500"), 1L),
        Tick("btcusdt", Money.of("50000"), 2L),  // layer 1 fills here
        Tick("btcusdt", Money.of("50100"), 3L),  // layer 2 triggers
        Tick("btcusdt", Money.of("50200"), 4L),  // layer 3 triggers
    )

    val result = Backtest(
        strategies = listOf("e2e" to onceStrategy("btcusdt", plan)),
        ticks = ticks,
    ).run()

    val buys = result.trades.filter { it.trade.side == Side.BUY }
    assertThat(buys).hasSize(3)
    val prices = buys.map { it.trade.price }.sortedBy { it }
    assertThat(prices[0]).isEqualByComparingTo(BigDecimal("50000"))
    assertThat(prices[1]).isEqualByComparingTo(BigDecimal("50100"))
    assertThat(prices[2]).isEqualByComparingTo(BigDecimal("50200"))
    assertThat(result.finalPositions["btcusdt"]?.quantity)
        .isEqualByComparingTo(BigDecimal("0.3"))
}
```

### Asserting per-layer fills at expected prices

Sort fills by price to avoid order-of-arrival sensitivity, then assert each expected fill price:

```kotlin
val prices = result.trades
    .filter { it.trade.side == Side.BUY }
    .map { it.trade.price }
    .sortedBy { it }
assertThat(prices).hasSize(3)
assertThat(prices[0]).isEqualByComparingTo(BigDecimal("50000"))
assertThat(prices[1]).isEqualByComparingTo(BigDecimal("50100"))
assertThat(prices[2]).isEqualByComparingTo(BigDecimal("50200"))
```

### Asserting SL-triggered cancellation of pending layers

A plan with a bracket SL that fires before layer 2's trigger price: assert only one buy trade and one sell trade (the SL exit), and that the final position is flat.

```kotlin
val plan = StackPlan(
    layers = ...,
    outerBracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("50")))),
)
val ticks = listOf(
    Tick("btcusdt", Money.of("50000"), 2L),  // layer 1 fills at anchor
    Tick("btcusdt", Money.of("49950"), 3L),  // SL fires, pending layers cancel
)
val result = Backtest(...).run()
assertThat(result.trades.filter { it.trade.side == Side.BUY }).hasSize(1)
assertThat(result.trades.filter { it.trade.side == Side.SELL }).hasSize(1)
assertThat(result.finalPositions["btcusdt"]).isNull()
```

### Mocking equity for RISK sizing tests

`OrderManagerStackTest` uses `FakeBroker` directly to test dispatch logic at unit level. For RISK-sizing layers that require equity resolution, provide a `StrategyContext` or a test `PnLProvider` with a fixed equity value, then assert the submitted quantity matches the expected fractional-risk calculation.

## Known limitations

- **Per-layer bracket override deferred.** The outer `BRACKET` clause applies identically to every layer. There is no syntax to give layer 2 a different SL distance than layer 1. Per-layer bracket overrides are a v2 concern.
- **Bare `STACK N` not supported.** `STACK 3` without a `SPACING` clause (iceberg / TWAP slicing) is a different primitive and is out of scope. A `SPACING` or layer-list is always required.
- **WHEN-condition per-layer triggers not supported.** Layers 2..N can only use price triggers (`AT <expr>`). Arbitrary boolean conditions per layer would require a full condition engine inside the layer scheduler and are deferred.
- **PIPS unit not supported.** `SPACING 100` means 100 raw price units (e.g., 100 USDT for a BTC pair quoted in USDT). A PIPS / points suffix is a language-wide feature affecting all distance clauses; it is out of scope for 13a.
- **Per-layer sizing evaluated at action-execute time.** `resolvedQuantity` in `LayerSpec` is populated by `ActionCompiler` when the strategy's action is compiled at signal time, not when each layer individually fires. For RISK/EQUITY% strategies this means layer 2's quantity is computed using the account state at signal time rather than at layer-2-fire time. The approximation is small in practice (equity doesn't change dramatically tick-to-tick) but is a known deviation from the ideal.
- **Stack state not persisted across daemon restarts.** In-flight stacks are not serialized. A daemon restart drops all pending layers. Strategies restart fresh with no knowledge of the prior stack.

## References

- Spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase13a-stack-design.md`
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase13a.md`
- Merge commit SHA: TBD (filled in after merge)
