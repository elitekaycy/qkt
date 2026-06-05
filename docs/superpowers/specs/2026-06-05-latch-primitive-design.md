# LATCH Primitive — Design

**Goal:** Add a `LATCH` entry primitive to the qkt DSL — a *directional trigger* that arms two price trip-wires, watches ticks engine-side, and on the first break resolves a **direction** and an **anchor** price `O`, then runs a **direction-relative** entry block. One trigger primitive, open order composition — so it powers the latch-stack retrace ladder, a pure breakout-market entry, and continuation strategies from the same construct.

**Architecture:** A new `Latch` AST node (sibling of `OcoEntry`) compiles each entry into a builder `(direction, anchor) -> OrderRequest`; a new per-tick `LatchManager` holds armed latches, detects the first wire crossed, and fans the entries out through the existing `OrderManager` order/bracket lifecycle. Everything after the trip reuses current machinery (`OrderRequest.Limit/StopLimit`, `BRACKET` `ChildAt`, `SIZING RISK`, GTD). Only the pre-trip state machine is new.

**Tech Stack:** Kotlin/JVM 21, the qkt DSL (`com.qkt.dsl.{ast,parse,compile}`), the engine (`com.qkt.app.{OrderManager,TradingPipeline}`), JUnit5 + AssertJ.

**Issue:** #264   **Branch:** `issue264-latch-primitive`

**Scope decision:** Build **Approach 2** (direction-relative entry block) with a clean seam toward Approach 3 (named-latch signal accessors). Approach 3 is *not* built now.

---

## Background — what already exists (reused, not rebuilt)

- **Order types** (`com.qkt.dsl.ast.ActionOpts` / `OrderTypeCompiler`): `Market`, `Limit(price)`, `Stop(price)`, `StopLimit(stop, limit)` — all compile to `OrderRequest.*` with expression prices.
- **Brackets** (`BracketAst`, `ChildPriceAst`): `ChildAt(price)` gives an **absolute-price** stop/target; `ChildArmedTrail` gives armed trailing.
- **Sizing** (`SizingAst` / `SizingCompiler`): `SizeRiskAbs` ( `SIZING RISK $ <expr>` ) sizes lots from a stop distance.
- **Expiry** (`TifAst.Gtd`): per-order GTD deadline (`phase-38-gtd-tif`).
- **Per-tick triggers**: `OrderManager.evaluateTriggers(tick: Tick)` (OrderManager.kt:1164) evaluates resting stop/limit/bracket/trailing triggers on **every tick**. Resting orders are therefore tick-fast with zero rule latency.
- **Multi-leg / OCO**: `OCO_ENTRY` (`parseOcoEntry`, first-fill-cancels-other) and `STACK` (sequential pyramiding) — both *adjacent* shapes, neither a fit for an independent direction-resolved fan-out (STACK doc: "if you want layers that fire independently, use separate rules").

The gap `LATCH` fills: *detect which way price breaks and place only that side's orders, anchored to the trigger, engine-side and tick-fast, without pre-committing the losing side.* No current construct does this.

---

## Section 1 — Grammar & direction-relative semantics

```
LATCH <stream> OFFSET <dist> ARM <duration> [FROM <price>] [AS <name>] {
    <entry> [ ; <entry> ]*
}

<entry> ::= ENTER <order> [BRACKET { [STOP LOSS <rel>] [, TAKE PROFIT <rel>] }]
                         [SIZING <sizing>] [EXPIRE <duration>]
<order> ::= MARKET | LIMIT <rel> | STOP <rel>
<rel>   ::= (WITH | AGAINST | RETRACE) <dist>      # RETRACE is a parser synonym for AGAINST
```

- **`OFFSET <dist>`** plants two trip-wires at `ref ± dist`. `ref` defaults to `<stream>.close` at rule-fire; `FROM <price>` overrides it.
- **`ARM <duration>`** — how long the wires stay live before the latch dissolves.
- **`AS <name>`** — optional latch name. v1 stores it (metadata only); the Approach-3 seam.

**Direction-relative prices.** Every price in the block is anchored to the trigger `O` and signed by the resolved direction. With `direction = +1` (LONG, up-break) or `−1` (SHORT, down-break):

| Keyword | Resolves to | Long (`O=close+offset`) | Short (`O=close−offset`) |
|---|---|---|---|
| `WITH d` | `O + direction·d` | `O + d` (with the break) | `O − d` (with the break) |
| `AGAINST d` / `RETRACE d` | `O − direction·d` | `O − d` (against) | `O + d` (against) |

`ENTER` resolves to **BUY** on an up-break, **SELL** on a down-break. The author never types a side or a sign.

**Worked example** (gold session close $2000.00, `offset 0.50`): up-break → `O = $2000.50`. `ENTER LIMIT RETRACE 4` → BUY LIMIT at `$1996.50`. `STOP LOSS AGAINST 12` → SL `$1988.50`. `TAKE PROFIT WITH 5` → TP `$2005.50`. A down-break mirrors all three around `O = $1999.50`.

### latch-stack expressed in it
```sql
THEN LATCH gold OFFSET offset ARM 5m {
    ENTER LIMIT RETRACE near BRACKET { STOP LOSS AGAINST sl, TAKE PROFIT WITH tp } SIZING RISK $ 250 EXPIRE 2h ;
    ENTER LIMIT RETRACE mid  BRACKET { STOP LOSS AGAINST sl, TAKE PROFIT WITH tp } SIZING RISK $ 250 EXPIRE 2h ;
    ENTER LIMIT RETRACE deep BRACKET { STOP LOSS AGAINST sl, TAKE PROFIT WITH tp } SIZING RISK $ 250 EXPIRE 2h
}
```

### The same primitive, other strategies (the payoff)
```sql
# Breakout momentum: enter at market on the break
LATCH gold OFFSET 0.50 ARM 5m {
    ENTER MARKET BRACKET { STOP LOSS AGAINST 10, TAKE PROFIT WITH 30 } SIZING RISK $ 200
}
# Continuation: add a stop further in the break direction
LATCH eur OFFSET 0.0010 ARM 10m {
    ENTER STOP WITH 5 BRACKET { STOP LOSS AGAINST 15, TAKE PROFIT WITH 40 } SIZING RISK $ 100
}
```

**Semantic rulings:**
- **Tiebreak** — if one tick straddles both wires (a gap through `close±offset`): resolve by the tick's travel vs the prior tick (printed higher → up-break; lower → down-break); on exact tie default to the up-wire. Defined, rare.
- **`ENTER MARKET`** fills at the trip (price `O`); `LIMIT`/`STOP` rest as direction-relative pendings via the existing order path.
- **Inverted-geometry guard** — at fan-out, skip + `WARN` any entry whose resolved order price lands on the wrong side of its own resolved stop (e.g. a `RETRACE d` with `d ≥ sl`), rather than place inverted orders. (pa-quant's "skip leg where `D ≥ sharedSl`".)

---

## Section 2 — Components (five units)

### 1. AST — new file `src/main/kotlin/com/qkt/dsl/ast/Latch.kt`
```kotlin
data class Latch(
    val stream: String,
    val sensor: LatchSensor,
    val armWindow: DurationAst,
    val name: String?,                 // AS <name>; v1 metadata only (Approach-3 seam)
    val entries: List<LatchEntry>,
) : ActionAst

sealed interface LatchSensor
data class BreakOffset(val reference: ExprAst?, val offset: ExprAst) : LatchSensor   // only member in v1

data class LatchEntry(
    val order: LatchOrder,
    val bracket: LatchBracket? = null,
    val sizing: SizingAst? = null,
    val expire: DurationAst? = null,
)
sealed interface LatchOrder
data object LatchMarket : LatchOrder
data class LatchLimit(val price: DirRel) : LatchOrder
data class LatchStop(val price: DirRel) : LatchOrder

enum class DirSense { WITH, AGAINST }            // RETRACE parses to AGAINST
data class DirRel(val sense: DirSense, val dist: ExprAst)
data class LatchBracket(val stopLoss: DirRel? = null, val takeProfit: DirRel? = null)
```
`ActionAst` must gain `Latch` as a member; `IterVarSubstitution` and any exhaustive `when (action)` sites must handle it (compile-time error surfaces them).

### 2. Parser — `com.qkt.dsl.parse`
- New `TokenKind`s: `LATCH, ENTER, OFFSET, ARM, WITH, AGAINST, RETRACE`. (`FROM`, `AS`, `EXPIRE`, `BRACKET`, `STOP`, `LOSS`, `TAKE`, `PROFIT`, `LIMIT`, `MARKET`, `SIZING`, `STREAM ident` already exist.)
- `parseLatch()` added to `parseAction`'s `when` (mirrors `parseOcoEntry` structure): parse `LATCH <ident> OFFSET <expr> ARM <duration> [FROM <expr>] [AS <ident>] { <entry> (; <entry>)* }`.
- `parseLatchEntry()`: `ENTER` then the order, optional `BRACKET`/`SIZING`/`EXPIRE`. Reuse `parseSizing`, `parseDuration`; `parseDirRel()` reads `(WITH|AGAINST|RETRACE) <expr>`.

### 3. Compiler — `com.qkt.dsl.compile.LatchCompiler`
Compiles `Latch` → `CompiledLatch`:
```kotlin
class CompiledLatch(
    val triggers: (EvalCtx) -> Pair<BigDecimal, BigDecimal>,  // (up=ref+offset, down=ref-offset)
    val armWindowMs: Long,
    val name: String?,
    val entryBuilders: List<LatchEntryBuilder>,
)
fun interface LatchEntryBuilder {
    // direction: +1 long / -1 short; anchor: O. Returns the concrete order (or null to skip).
    fun build(direction: Int, anchor: BigDecimal, ctx: EvalCtx): OrderRequest?
}
```
Each builder resolves: side (BUY if direction>0 else SELL), the order price (`O ± direction·dist` via `DirRel`), the bracket prices (`ChildAt` absolute, from `O ± direction·dist`), the sizing (`SizingCompiler`), and the GTD expiry — then constructs the same `OrderRequest.Limit/Stop/Market` the normal path builds. The inverted-geometry guard returns `null` (skip + WARN). `ref` defaults to a `StreamFieldRef(stream, "close")` when `BreakOffset.reference == null`.

### 4. Engine — `com.qkt.app.LatchManager`
The one new state machine; evaluated per tick.
```kotlin
private data class ArmedLatch(
    val symbol: String,
    val up: BigDecimal,
    val down: BigDecimal,
    val expiresAt: Long,
    val compiled: CompiledLatch,
    val strategyId: String,
)
class LatchManager(private val emit: (OrderRequest) -> Unit, private val clock: Clock) {
    fun arm(compiled: CompiledLatch, ctx: EvalCtx, strategyId: String) { /* compute triggers+expiry, store */ }
    fun onTick(tick: Tick) {
        // for each armed latch on tick.symbol:
        //   now >= expiresAt           -> remove (dissolve, no orders)
        //   tick.price >= up           -> fire(+1, up)
        //   tick.price <= down         -> fire(-1, down)   (tiebreak per Section 1)
    }
    private fun fire(direction: Int, anchor: BigDecimal, latch: ArmedLatch) {
        latch.compiled.entryBuilders.forEach { b -> b.build(direction, anchor, ctx)?.let(emit) }
        // remove the latch (spent)
    }
}
```
Tripped orders flow through `emit` into the existing `OrderManager` pending/bracket lifecycle. `LatchManager` owns only the pre-trip phase.

### 5. Wiring — `com.qkt.app.TradingPipeline`
- Construct a `LatchManager` beside `OrderManager`; subscribe it to `TickEvent` (same feed as `OrderManager.evaluateTriggers`).
- Arming via the existing emit channel: the compiled `Latch` action emits a `LatchArm(compiled, strategyId)` that the pipeline routes to `LatchManager.arm()` — consistent with how compiled actions emit `OrderRequest`s. (Add `LatchArm` to the emit sum or a sibling callback; pick whichever matches the current `emit` signature with least churn — confirm during planning.)

### Data flow
`:55 rule fires → action emits LatchArm → LatchManager stores ArmedLatch → ticks → first wire crossed → entryBuilders fan out OrderRequests → OrderManager owns them.`

---

## Section 3 — Restart behavior (deliberate v1 simplification)

Armed latches are **transient** — not persisted. An arm window is minutes; a daemon restart mid-arm drops the un-tripped latch and the strategy re-arms next session. Once a latch *trips*, its orders are normal pendings/brackets and **are** persisted by `OrderManager`, so a restart never loses real exposure — only an in-flight, order-less wire. Full arm-state persistence (serialize `ArmedLatch` triggers/expiry/entry-index, re-link to the recompiled strategy on load) is a clean later add, intentionally out of scope here.

---

## Section 4 — Error handling

- **Compile-time:** missing `OFFSET`/`ARM`, an `ENTER` without an order, a `DirRel` without a distance → parser/compiler error with location.
- **Fan-out:** a single entry whose sizing or geometry is invalid is skipped with a `WARN` (inverted-geometry guard); the other entries still place — one bad leg never sinks the latch. Mirrors STACK's per-layer rejection.
- **No double-arm:** the arming rule guards itself in the DSL (`POSITION.<stream> = 0` etc., as today); `LatchManager` does not dedupe — re-arming replaces nothing, it adds (authors gate with the WHEN condition).

---

## Section 5 — Testing (real data, no mocks)

- **Parser tests** (`ParserLatchTest`): the grammar parses; `RETRACE`→`AGAINST`; `FROM`/`AS`/`EXPIRE` optional; malformed forms error.
- **Compiler tests** (`LatchCompilerTest`): direction-relative resolution for **both** directions — `ENTER LIMIT RETRACE 4` yields `O−4` long and `O+4` short; bracket `ChildAt` prices correct both ways; inverted-geometry entry compiles to a skip.
- **`LatchManager` unit tests**: arm→up-cross→correct BUY orders emitted; arm→down-cross→mirrored SELL orders; arm-expiry→zero orders; the tiebreak; the inverted-geometry skip.
- **End-to-end backtest** (`LatchBacktestTest`, real synthetic ticks): an up-break-then-retrace path → ladder places, fills on the pullback, brackets exit; a down-break path for the mirror; a no-break path → nothing placed.
- ktlint clean (blank line before standalone comments).

---

## Section 6 — The Approach-3 seam (unbuilt, unblocked)

The `name` is parsed and `LatchManager` already holds per-latch state. Adding later, with no rework here:
- `LATCH.<name>.direction | anchor | tripped` read accessors (a new `ExprCompiler` accessor reading `LatchManager` state, like `POSITION.*`).
- A `LatchTripped` event other rules subscribe to.

That turns the latch into a cross-rule signal (Approach 3) when a consumer strategy actually needs it.

---

## File structure

| File | Responsibility |
|---|---|
| `dsl/ast/Latch.kt` (new) | `Latch`, `LatchSensor`, `LatchEntry`, `LatchOrder`, `DirRel`, `LatchBracket` |
| `dsl/parse/TokenKind.kt` (modify) | new tokens |
| `dsl/parse/Parser.kt` (modify) | `parseLatch`, `parseLatchEntry`, `parseDirRel` |
| `dsl/compile/LatchCompiler.kt` (new) | `Latch` → `CompiledLatch` + entry builders |
| `dsl/compile/ActionCompiler.kt` (modify) | dispatch `Latch` |
| `app/LatchManager.kt` (new) | armed-latch state machine, per-tick fan-out |
| `app/TradingPipeline.kt` (modify) | construct + wire `LatchManager`; route `LatchArm` |
| tests (new) | parser, compiler, manager, backtest |

## Sequencing
1. AST + parser (grammar parses, round-trips).
2. Compiler + builders (direction-relative resolution, unit-tested without the engine).
3. `LatchManager` + wiring (arm → tick → fan-out).
4. End-to-end backtest.
5. Docs (`docs/reference/dsl/latch.md`) + an `examples/latch-stack/latch-stack.qkt`.
