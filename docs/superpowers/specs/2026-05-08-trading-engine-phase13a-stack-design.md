# Phase 13a — STACK: Pyramiding / Scaling-In Order Modifier

> Status: design accepted, awaiting plan.
> Author: elitekaycy.
> Phase: 13a (DSL + execution; first sub-phase of phase 13).

---

## 1. Mission

Add a `STACK` keyword that lets a single `BUY` or `SELL` action express **scaling into a position over time as price moves**. STACK turns one order line into N entries fired at deterministic price triggers, with an optional time fence and per-layer sizing. It is a strategy/engine-side primitive — brokers continue to see only ordinary order types.

The pattern STACK targets is the everyday discretionary practice of pyramiding into trends and averaging into pullbacks: "buy 0.1, then add another 0.1 every 100 points of trend, give up if it doesn't trend within an hour." Strategies today have to express this with custom state machines built out of WHEN/THEN rules. STACK collapses that into one line.

---

## 2. Goals

### Functional

- One-line expression of price-triggered pyramiding inside a `BUY` or `SELL` action.
- Two surface forms: a compact **count + spacing** form, and an explicit **layer-list** form.
- Optional time fence (`WITHIN <duration>`) that cancels remaining pending layers on expiry.
- Per-layer sizing in any existing form (qty, RISK, NOTIONAL, % EQUITY, etc.), evaluated when the layer fires.
- Pyramid (with trend) and average-down semantics via `ABOVE` / `BELOW` keywords.
- Deterministic backtest behaviour: same anchor, same trigger logic, same fire ordering as live.

### Non-functional

- Zero new tokens beyond what STACK strictly needs (`STACK`, `SPACING`, `WITHIN`, `DURATION` literal). `ABOVE` / `BELOW` already exist.
- Internal Kotlin DSL parity with the external parser; round-trip equivalence enforced by tests.
- Reuse existing `OrderManager` machinery (PENDING orders, tick-driven trigger evaluation, OTO parent-fills-children, TimeExit deadline) rather than introducing parallel runners.
- Brokers receive ordinary order types (Market / Limit / Stop / Bracket); no broker integration changes.

### Non-goals (deferred to v2 or later)

- **Bare `STACK N` with no trigger** (slicing / iceberg / TWAP). Those are different primitives — STACK is strictly for price-triggered scaling.
- **`WHEN <expr>` per-layer triggers** (arbitrary boolean expressions). Adds a real condition engine inside the layer scheduler; not worth it in v1.
- **Per-layer bracket overrides** (different SL/TP per layer). Multiplies implementation surface; v1 inherits the outer bracket per layer.
- **Per-layer TIF override.** Same reason.
- **Aggregate exit** (one shared SL/TP closing all open layers atomically as one position). v1 uses per-layer brackets (D1).
- **PIPS / unit suffix on price distances.** Distances stay raw price-units (`SPACING 100` = 100 base units). PIPS is a separate language feature affecting all distance clauses; out of scope here.
- **Hard maximum layer count.** v1 trusts the user; sanity check in error messages above 50.

---

## 3. Worked examples

### 3.1 SPACING form — pyramid into trend

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

Behaviour:
- Layer 1 fires at market when the EMA crosses up.
- Layer 1 fills at, say, 50000 (anchor).
- Pending layers 2 and 3 are submitted with trigger prices `50100` (anchor + 100) and `50200` (anchor + 200) respectively.
- Each layer becomes a Bracket order with `SL = fill - 50`, `TP = fill + 200` (per-layer).
- If price climbs to 50100, layer 2 fires; if to 50200, layer 3 fires.
- If layer 1's SL fires at 49950 before layer 2's trigger is met, layers 2 and 3 cancel automatically.

Total exposure at full stack: 0.3 BTC. Each layer is 0.1.

### 3.2 SPACING form — average down with `BELOW`

```
WHEN rsi(btc.close, 14) < 30
THEN BUY btc SIZING 0.1
     STACK 3 SPACING 100 BELOW
     BRACKET { STOP LOSS BY 200, TAKE PROFIT BY 300 }
```

Layers 2 and 3 trigger at `anchor - 100` and `anchor - 200` (averaging down).

### 3.3 Layer-list form — explicit per-layer entries

```
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc STACK [
       0.1,                                     -- layer 1: market
       0.2 AT entry + 100,                      -- layer 2: triggered market at +100
       0.3 LIMIT AT entry + 200,                -- layer 3: triggered limit at +200
     ]
     BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

`entry` is a magic identifier valid only inside layer-list AT expressions; resolves to layer 1's actual fill price.

Layer 1 fires at market. Layers 2 and 3 are submitted as pending orders once layer 1 fills, with trigger prices computed from `entry`.

Note: in the layer-list form the outer `BUY btc` carries no `SIZING` clause — each layer carries its own sizing. The conflict rule (outer `SIZING` + per-layer sizing) is enforced at parse time.

### 3.4 Layer-list with mixed sizing

Sizing forms in qkt today: `<expr>` (qty) / `<expr> USD` (notional) / `<expr> % OF EQUITY` / `<expr> % OF BALANCE` / `RISK <frac-expr>` / `RISK $<usd-expr>`. STACK reuses these unchanged.

```
BUY btc STACK [
  0.1 AT entry,                    -- qty
  RISK 0.01 AT entry + 100,        -- 1% fractional risk
  5000 USD AT entry + 200,         -- notional
  RISK $500 AT entry + 300,        -- absolute risk
  2 % OF EQUITY AT entry + 400,    -- % equity
]
BRACKET { STOP LOSS BY 50 }
```

Each layer's sizing is evaluated when that layer fires. RISK sizings use per-layer SL prices derived from the outer `BRACKET { STOP LOSS BY 50 }` applied to each layer's expected entry.

### 3.5 WITHIN time fence

```
BUY btc SIZING 0.1
STACK 3 SPACING 100 WITHIN 1h
BRACKET { STOP LOSS BY 50 }
```

If, 1h after layer 1's fill, layers 2 and 3 still haven't fired, both pending layers cancel. Already-filled layers (only layer 1 in that case) keep their brackets and continue to live their normal lifecycle.

### 3.6 SELL symmetry

```
WHEN rsi(btc.close, 14) > 70
THEN SELL btc SIZING 0.1
     STACK 3 SPACING 100
     BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

For SELL, default direction is **downward** (with-trend = price falling). `ABOVE` / `BELOW` work identically (relative to entry); they reverse trade-direction default.

### 3.7 Concurrent stacks

```
RULES
    WHEN ema_cross_up THEN BUY btc SIZING 0.1 STACK 3 SPACING 100
    WHEN flag_x       THEN BUY btc SIZING 0.05 STACK 2 SPACING 50
```

Both rules can fire while either's stack is in flight. Each rule spawns its own independent StackOrchestrator. Layer fills, brackets, and cancellations are tracked per-stack — one stack's TP closing its layers does not cancel another stack's pending layers.

### 3.8 FOR/EACH composition

```
RULES
    FOR EACH s IN [btc, eth, sol] DO
        WHEN ema(s.close, 9) CROSSES ABOVE ema(s.close, 21)
        THEN BUY s SIZING 0.1 STACK 3 SPACING 100
```

Each iteration generates its own STACK-bearing rule; stacks fire independently per symbol. No special handling.

---

## 4. Architecture

### 4.1 AST additions

Under `com.qkt.dsl.ast`:

```kotlin
data class ActionOpts(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val bracket: BracketAst? = null,
    val oco: OcoAst? = null,
    val stack: StackAst? = null,        // NEW
)

sealed interface StackAst

data class StackSpacing(
    val count: Int,                     // >= 1
    val spacing: ExprAst,               // unsigned magnitude expression
    val direction: StackDirection,
    val within: DurationAst? = null,
) : StackAst

data class StackLayers(
    val layers: List<StackLayer>,
    val within: DurationAst? = null,
) : StackAst

enum class StackDirection { TRADE_DIRECTION, ABOVE, BELOW }

data class StackLayer(
    val sizing: SizingAst,
    val orderType: OrderTypeAst? = null,   // null = Market
    val at: ExprAst? = null,               // null = fire immediately (only valid for layer 1)
)

data class DurationAst(val millis: Long) {
    init { require(millis > 0) { "duration must be positive: $millis" } }
}
```

Validation rules enforced at parse time and in Kotlin builders:

- `StackSpacing.count` >= 1.
- `StackSpacing.spacing` literal `0` rejected; runtime evaluation of `0` aborts the stack with an error event.
- `StackLayers.layers` non-empty.
- Layer 1 may omit `at` (fires at market). Layers 2..N must include `at`.
- Outer `BUY <stream> <sizing>` together with `STACK [<layers>]` (each layer carrying its own sizing) is a parse error — pick one place to specify size.
- `WITHIN` without `SPACING` or `[layers]` is a parse error.

### 4.2 Lexer additions

New token kinds:

| Kind | Lexeme(s) |
|---|---|
| `STACK` | `STACK` |
| `SPACING` | `SPACING` |
| `WITHIN` | `WITHIN` |
| `DURATION` | digits + `s\|m\|h\|d`, no whitespace, e.g., `15s`, `30m`, `1h`, `2d` |

`ABOVE`, `BELOW`, `LBRACKET`, `RBRACKET`, `AT`, `BY`, `LIMIT`, `STOP`, `MARKET`, `COMMA` already exist; reused.

`entry` is recognized as an `IDENT` lexeme; the parser interprets it as a magic identifier when it appears inside a layer-list `AT <expr>` clause and rejects it elsewhere.

### 4.3 Grammar (BNF-ish)

```
buy_action      ::= "BUY"  IDENT action_opts
sell_action     ::= "SELL" IDENT action_opts

action_opts     ::= ( sizing_clause | order_type_clause | tif_clause
                    | bracket_clause | oco_clause | stack_clause )*

sizing_clause   ::= "SIZING" sizing
stack_clause    ::= "STACK" ( stack_spacing | stack_layers )

stack_spacing   ::= integer "SPACING" expr direction? within_clause?
direction       ::= "ABOVE" | "BELOW"
within_clause   ::= "WITHIN" duration

stack_layers    ::= "[" layer ("," layer)* ","? "]" within_clause?
layer           ::= sizing order_type? ("AT" expr)?

duration        ::= DURATION    -- e.g., 1h, 30m
```

Order in `action_opts`: free order; STACK can appear before or after bracket/oco/etc. The parser consumes whatever clauses it sees, attaching them to `ActionOpts`. The conflict rule "outer `SIZING` clause AND `STACK` layer-list" is enforced after the action_opts loop completes.

### 4.4 Engine integration

A new `OrderRequest.Stack` variant joins the existing `OrderRequest` sealed interface:

```kotlin
data class Stack(
    override val id: String,
    override val symbol: String,
    override val side: Side,
    override val quantity: BigDecimal,        // sum of layer qty estimates (informational)
    val plan: StackPlan,
    val outerBracket: BracketAst? = null,     // applied per-layer
    val withinMillis: Long? = null,
    override val timeInForce: TimeInForce,
    override val timestamp: Long,
    override val strategyId: String = "",
) : OrderRequest
```

`StackPlan` is the runtime IR — flattened from either form into an ordered list of `LayerSpec`s:

```kotlin
data class StackPlan(val layers: List<LayerSpec>)

data class LayerSpec(
    val index: Int,
    val sizing: SizingPlan,            // pre-resolved sizing form
    val orderType: OrderTypeAst,       // Market by default
    val trigger: LayerTrigger,         // Immediate | At(price expression)
)

sealed interface LayerTrigger
data object Immediate : LayerTrigger    // layer 1 only
data class At(val price: ExprAst, val direction: StackDirection) : LayerTrigger
```

The compiler folds the SPACING form into N layer specs:
- Layer 1: `Immediate` trigger.
- Layers 2..N: `At(entry + spacing * i, direction)` triggers, where `entry` resolves to the first-fill price at runtime.

### 4.5 OrderManager dispatch

`OrderManager.dispatch` gains a `Stack` arm:

```kotlin
is OrderRequest.Stack -> submitStack(request)
```

`submitStack` does:
1. Submit layer 1 as the appropriate concrete order (Market / Limit / Stop) wrapped in a Bracket if `outerBracket` is present.
2. Track the stack in a per-strategy `stacks: MutableMap<String, ActiveStack>` registry.
3. Subscribe to the layer-1 fill event. On fill:
   - Capture anchor = layer 1 fill price.
   - For each pending layer, resolve its trigger expression against `entry = anchor`, producing a concrete trigger price.
   - Submit each as a PENDING order (Stop or Limit depending on direction and orderType) with the per-layer bracket inherited from `outerBracket`.
   - If `withinMillis` is set, register a deadline timer; at expiry, cancel all of this stack's pending layers.
4. Subscribe to fill events for each layer's bracket SL/TP. When all of this stack's filled layers are closed (position-flat for the stack), cancel any remaining pending layers and terminate the stack.
5. On strategy stop or external `CANCEL` / `CANCEL_ALL` / `CLOSE` / `CLOSE_ALL` of the stream: terminate the stack — cancel pending layers, leave already-filled layers' brackets to live their lifecycle (bracket cancel/close is handled by the existing CANCEL/CLOSE flow, not by the stack).

`ActiveStack` runtime state:

```kotlin
private data class ActiveStack(
    val id: String,
    val plan: StackPlan,
    val outerBracket: BracketAst?,
    val withinMillis: Long?,
    val anchor: BigDecimal? = null,         // null until layer 1 fills
    val pendingLayerIds: MutableSet<String> = mutableSetOf(),
    val filledLayerIds: MutableSet<String> = mutableSetOf(),
    val deadlineEpochMs: Long? = null,
)
```

The `evaluateTriggers(tick)` loop already iterates pending orders per tick. Pending layer orders are ordinary `Stop` or `Limit` from OrderManager's perspective and use the existing trigger evaluation. No new tick-evaluation code path.

The deadline check folds into the existing TimeExit deadline scan in `evaluateTriggers`:

```kotlin
val now = clock.now()
for ((stackId, stack) in stacks.entries.toList()) {
    val deadline = stack.deadlineEpochMs ?: continue
    if (now < deadline) continue
    cancelStackPending(stackId)
}
```

### 4.6 Per-stack flat detection

When a layer's bracket SL or TP fires, OrderManager already publishes `OrderFilled` events for the SL/TP order. The Stack-aware part of OrderManager subscribes to those events and tracks per-stack:

- Each filled layer order ID is recorded in `filledLayerIds`.
- Each filled layer's bracket children's terminal events (filled or cancelled) decrement an open-positions counter for the stack.
- When the counter reaches zero AND `filledLayerIds` is non-empty (i.e., at least one layer actually entered), the stack's position is flat → cancel pending layers + terminate the stack.

This keeps stack flat-detection independent from any other stack on the same stream.

### 4.7 Determinism

- Same tick stream → same anchor (layer 1 fills at the same price) → same per-layer trigger prices → same fire timing.
- WITHIN is wall-clock relative to the layer 1 fill timestamp. In backtests the "wall clock" is the simulated tick clock, so WITHIN is deterministic.
- Sizing evaluations at fire time use only state derivable from the deterministic state machine (positions, equity, expected entry).

### 4.8 Logging / observability

Each layer fire emits a log entry:

```
INFO stack stack_id=stk_xyz strat_id=btc_pyramid layer=2/3 trigger=50100.0 fill=50102.5 qty=0.1
```

Status snapshot (added by 12b at `/status`) gains a new field per strategy:

```json
{
  "strategy": "btc_pyramid",
  "pendingStackLayers": [
    {"stackId": "stk_xyz", "layer": 3, "trigger": 50200.0, "side": "BUY", "qty": 0.1}
  ]
}
```

---

## 5. Kotlin DSL surface

Under `com.qkt.dsl.kotlin`:

```kotlin
// Builder helpers
fun stack(
    count: Int,
    spacing: ExprAst,
    direction: StackDirection = StackDirection.TRADE_DIRECTION,
    within: DurationAst? = null,
): StackSpacing

fun stackOf(
    vararg layers: StackLayer,
    within: DurationAst? = null,
): StackLayers

fun layer(
    qty: ExprAst,
    orderType: OrderTypeAst? = null,
    at: ExprAst? = null,
): StackLayer

fun layer(
    sizing: SizingAst,
    orderType: OrderTypeAst? = null,
    at: ExprAst? = null,
): StackLayer

// Magic identifier
val entry: ExprAst   // top-level val; only meaningful inside layer.at expressions

// Duration helper
fun duration(text: String): DurationAst    // parses "1h" / "30m" / etc.
fun duration(amount: kotlin.time.Duration): DurationAst
```

`ActionScope.buy` / `ActionScope.sell` gain a `stack: StackAst? = null` parameter, threaded into `ActionOpts`. Existing overloads remain; the parameter is optional in every overload.

Validation in builders mirrors parser validation:

- `stack(count = ...)`: `count >= 1`; `spacing` cannot be a literal zero (we can't statically catch arbitrary expressions evaluating to zero — that's a runtime check).
- `stackOf(...)`: `layers` non-empty; layers 2..N must have non-null `at`; layer 1 must have either `at` or null `at` (= immediate market).
- Outer sizing + per-layer sizing → IllegalStateException at build time.

---

## 6. Testing strategy

Test files under `src/test/kotlin/com/qkt/dsl/`, `src/test/kotlin/com/qkt/execution/`, and `src/test/kotlin/com/qkt/app/` (integration).

### 6.1 Lexer tests

- `STACK` / `SPACING` / `WITHIN` keyword tokens recognized (case-sensitive uppercase, matching the rest of the language).
- DURATION literals parsed: `1s` / `15s` / `30m` / `1h` / `2d`. Ranges: 1..max. Reject `0h`, `1.5h`, `1H` (lowercase only on suffix).
- `entry` lexed as IDENT; parser handles the rest.

### 6.2 Parser tests

- SPACING form parsing: `BUY btc 0.1 STACK 3 SPACING 100` → `StackSpacing(count=3, spacing=Number(100), direction=TRADE_DIRECTION)`.
- ABOVE / BELOW direction parsed.
- WITHIN parsed and attached.
- Layer-list parsing: simple list, with order types, with mixed sizing.
- Magic `entry` resolved inside layer AT expressions; rejected outside.
- Conflict cases reject:
  - Outer sizing + per-layer sizing.
  - WITHIN without SPACING or layer-list.
  - Layer 2 with no AT clause.
  - SPACING 0 literal.
  - Empty layer list.
  - Negative SPACING expression literal (e.g., `SPACING -100`) — the magnitude must be unsigned.

### 6.3 Compiler tests

- SPACING form folded into N layer specs with correct trigger expressions.
- Layer-list form preserved as-is.
- Direction propagated to triggers.
- DurationAst converted from text to millis correctly (`1h` → 3_600_000).

### 6.4 OrderManager tests

- Stack with 3 layers, SPACING form: layer 1 fires immediately, layer 2 pending until trigger price, layer 3 pending until trigger price.
- Layer 1 fills at 50000; layers 2 and 3 trigger prices recomputed to 50100 / 50200 (per A1).
- Tick at 50100 fires layer 2; tick at 50200 fires layer 3.
- WITHIN deadline fires: pending layers cancelled, filled layers untouched.
- Layer 1 SL fires before layer 2's trigger: layer 1 closes, pending layers 2 and 3 cancel automatically (per-stack flat detection).
- TP on layer 1 fires before layer 2: layer 1 closes, pending layers cancel.
- External CANCEL on the stream: pending layers cancel, stack terminates.
- External CLOSE on the stream: filled layers exit at market, pending layers cancel.
- Concurrent stacks: stack A's TP does not cancel stack B's pending layers (per-stack scope).
- Strategy stop: all stacks for the strategy terminate cleanly.

### 6.5 Sizing tests

- Per-layer qty sizing: each layer fires at its qty.
- Per-layer RISK%: each layer's quantity computed against the outer SL applied to its own expected entry.
- Per-layer NOTIONAL USD: sized at fire time against current price.
- Per-layer mixed sizing forms in one layer list.
- Sizing with no bracket SL but RISK form: error at fire time (existing behaviour).

### 6.6 Round-trip equivalence (extension of 11f tests)

- Each STACK form: parser-produced strategy must equal Kotlin-DSL-produced strategy.
- Both forms (SPACING + layer-list).
- Direction variants.
- WITHIN.
- Mixed sizing in layer list.

### 6.7 End-to-end backtest tests

- Synthetic tick stream: entry at 50000, climb to 50300, fall back. Expected: 3 layers fill at 50000 / 50100 / 50200; bracket SLs fire on the descent.
- Synthetic stream where the trend reverses after layer 1: layer 1 stops out at SL, pending layers 2 and 3 cancel automatically.
- WITHIN expiry: stream where price stalls; deadline fires; pending layers cancel.
- Determinism check: same seed + same tick stream → identical layer fills (price + timestamp + qty).

### 6.8 Live smoke (manual, marked with `@Tag("e2e-live")`)

- A single integration run against a paper-trading endpoint that exercises a 2-layer SPACING stack on a low-priced symbol. Verifies the live path matches backtest expectations within slippage tolerances (informational — no hard assertion, just log-comparison).

---

## 7. Risk

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Per-stack flat detection has subtle bugs (e.g., partial fills, broker rejections) | Medium | High | Extensive OrderManager unit tests cover partial-fill, rejection-after-pending-submit, and out-of-order event paths. Integration tests against real Bybit paper. |
| RISK sizing misbehaves at fire time when expected entry cannot be inferred (e.g., stop layer that hasn't triggered yet but sizing evaluates against stale price) | Low | Medium | Define expected-entry rule explicitly: for triggered layers, expected-entry = trigger price; for market layers, expected-entry = current tick. Sizing evaluation uses that. Unit-tested. |
| Concurrent stacks confuse position trackers if naming collides | Low | Medium | Per-stack `id` namespace for layer order IDs (`stk_<uuid>-layer-<n>`); per-strategy stack registry isolated. |
| WITHIN deadline timing in backtests vs live differs | Low | High | Deadline computed in milliseconds against the engine clock (which is `FixedClock` or simulated in backtests, real in live). Same code path. Unit tests for both. |
| User writes infinite layer count (DoS via large layer-list) | Low | Low | Soft cap: parse-time warning above 50 layers. No hard cap; user error. |
| Lexer ambiguity between `1h` (duration) and `1` followed by identifier `h` | Low | Low | DURATION token matched greedily before NUMBER+IDENT split. Unit-tested. |

---

## 8. Phase decomposition (preview for the plan)

The plan will be structured around these task clusters:

1. **Lexer and parser** — token kinds, parser rules, parse errors. Pure parser, no engine work. (~3 tasks, including round-trip-aware parse tests.)
2. **AST + Kotlin DSL** — `StackAst`, `StackLayer`, `DurationAst`, `entry` magic id, builder helpers. (~2 tasks.)
3. **Compiler** — fold SPACING into layer specs; resolve `entry` in layer AT expressions; build `OrderRequest.Stack`. (~2 tasks.)
4. **OrderManager `Stack` dispatch** — submit logic, pending-layer materialization on layer-1-fill, per-stack flat detection, WITHIN deadline. (~4 tasks.)
5. **Per-layer sizing at fire time** — RISK, NOTIONAL, EQUITY% evaluation per layer with per-layer expected entry. (~1 task.)
6. **Cancellation interactions** — CANCEL / CLOSE / CLOSE_ALL / strategy-stop handling for stacks. (~1 task.)
7. **Observability** — log entries per layer fire; status snapshot includes pending stack layers. (~1 task.)
8. **Round-trip equivalence tests** — extend 11f fixtures with STACK cases. (~1 task.)
9. **End-to-end backtest tests** — synthetic-tick scenarios. (~2 tasks.)
10. **Phase changelog** — `docs/phases/phase-13a-stack.md`.

Estimated 17–18 tasks. Roughly the same scope as 11e.

---

## 9. Out of scope (explicit)

- WHEN-condition layer triggers.
- Bare `STACK N` slicing (no trigger).
- Per-layer bracket / TIF override.
- Aggregate exit semantics (one bracket for all layers as one position).
- PIPS unit on distances.
- STACK on `CLOSE` actions (e.g., scaling out — that's `ScaleOut`, already exists).
- Cross-strategy STACK coordination.
- STACK persistence across daemon restarts (each restart starts strategies fresh; in-flight stacks are not resumed).
- Hard maximum layer count enforcement.
- New broker primitives or capabilities.

---

## 10. References

- 11e (multi-stream / multi-timeframe / CompositeBroker): `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`
- 11f (external SQL-like parser): `docs/superpowers/specs/2026-05-08-trading-engine-phase11f-design.md`
- 12c (daemon, multi-strategy hosting): `docs/superpowers/specs/2026-05-08-trading-engine-phase12c-design.md`
- Existing OrderManager tick-driven trigger evaluation: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Existing OTO / ScaleOut / TimeExit patterns: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
