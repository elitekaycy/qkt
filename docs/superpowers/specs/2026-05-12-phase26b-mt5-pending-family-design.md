# Phase 26b — MT5 native pending family + OCO + trailing

> Spec for closing the broker gap between the qkt DSL and live MT5 execution. Phase 26a made `OCO_ENTRY` and `NOW.<field>` reachable from the DSL and backtest-clean. Phase 26b makes the corresponding `OrderRequest` shapes route natively through `MT5OrderTranslator` so the same strategies run live on Exness, ICMarkets, FTMO, or Pepperstone.

## Goal

Make every `OrderRequest` shape that the DSL emits today route natively to MT5 — `Stop`, `Limit`, `StopLimit`, `StandaloneOCO`, `TrailingStop` — plus verify the `OrderManager` cancel-on-fill propagates correctly across MT5 tickets. Stop relying on `error("MT5 v1 does not natively translate ...")` for these shapes. Hedge-straddle (Phase 26a strategy file) goes live as a direct consequence.

## Motivation

Reading `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt:12-20`:

```kotlin
fun translate(req: OrderRequest): MT5OrderRequest =
    when (req) {
        is OrderRequest.Market -> translateMarket(req)
        is OrderRequest.Bracket -> translateBracket(req)
        else ->
            error(
                "MT5 v1 does not natively translate ${req::class.simpleName}; " +
                    "OrderManager should use engine-managed fallback",
            )
    }
```

Five pending-order shapes are DSL-accessible but broker-rejected on MT5:

| Shape | DSL surface | Status on MT5 |
| --- | --- | --- |
| `Stop` | `ORDER_TYPE = STOP AT <price>` | rejected |
| `Limit` | `ORDER_TYPE = LIMIT AT <price>` | rejected |
| `StopLimit` | `ORDER_TYPE = STOP AT X LIMIT AT Y` | rejected |
| `StandaloneOCO` | Phase 26a `OCO_ENTRY { ... }` | rejected |
| `TrailingStop` | `ORDER_TYPE = TRAILING BY <amt>` / `TRAILING PCT <frac>` | rejected |

This means hedge-straddle (Phase 26a's reference example), any limit-entry strategy, and any trailing-exit strategy cannot run live on MT5 today — even though the engine, DSL, and `PaperBroker` all support them.

The "engine-managed fallback" mentioned in the error message is wrong for these shapes when running live. Engine-managed means qkt's `OrderManager` watches prices itself and submits market orders when a synthetic trigger fires — fine for backtest, but fragile for live (network latency between qkt and MT5 means the synthetic stop can fire on a stale tick, executing at a worse price than a native MT5 stop would have). For live MT5, we want native broker-side triggers: `ORDER_TYPE_BUY_STOP`, `ORDER_TYPE_SELL_STOP`, etc.

## Scope

### In scope

**A. Native translation of pending shapes** (~250 LOC + tests)

Five new translation methods in `MT5OrderTranslator`:

```kotlin
fun translate(req: OrderRequest): MT5OrderRequest =
    when (req) {
        is OrderRequest.Market -> translateMarket(req)
        is OrderRequest.Bracket -> translateBracket(req)
        is OrderRequest.Stop -> translateStop(req)
        is OrderRequest.Limit -> translateLimit(req)
        is OrderRequest.StopLimit -> translateStopLimit(req)
        is OrderRequest.TrailingStop -> translateTrailingStop(req)
        is OrderRequest.StandaloneOCO -> translateStandaloneOCO(req)
        else -> error("MT5 does not translate ${req::class.simpleName}")
    }
```

Each translator builds the appropriate MT5 wire payload:

- **`Stop`** → `BUY_STOP` / `SELL_STOP` with `price` = trigger
- **`Limit`** → `BUY_LIMIT` / `SELL_LIMIT` with `price` = limit
- **`StopLimit`** → MT5 has `ORDER_TYPE_BUY_STOP_LIMIT`. `price` = stop, `stoplimit` = limit.
- **`StandaloneOCO`** → splits into two requests, returns a `Composite` (see below). Both tagged with the same `groupId` so the OrderManager's sibling cancel-on-fill works against MT5 tickets.
- **`TrailingStop`** → MT5's `sl_distance` trailing parameter (`OrderRequest.TrailingStop.trailMode` maps to either absolute distance or percent, both supported).

**B. OCO composite handling** (~80 LOC + tests)

`MT5OrderTranslator.translate` currently returns a single `MT5OrderRequest`. For OCO, we need to return two. Options:

1. Change the return type to `List<MT5OrderRequest>` — breaking change to existing call sites
2. Add a sealed wrapper `MT5Translation` with `Single(req)` and `Composite(reqs, groupId)` variants
3. Have `translateStandaloneOCO` submit both legs via a new explicit `MT5Broker.submitOco` path

Recommend (2) — sealed wrapper. Minimally invasive, type-safe, no surprise behavior at existing sites.

```kotlin
sealed interface MT5Translation {
    data class Single(val request: MT5OrderRequest) : MT5Translation
    data class Composite(
        val requests: List<MT5OrderRequest>,
        val groupId: String,
    ) : MT5Translation
}
```

`MT5Broker.submit` receives the translation and either sends one wire request or two with the same group tag. The `OrderManager`'s sibling map (`siblings[clientOrderId]`) is already populated by `submitOco` in Phase 26a; this phase just wires the MT5 side.

**C. MT5Broker.submit expansion** (~40 LOC + tests)

Today line 57:

```kotlin
if (request !is OrderRequest.Market && request !is OrderRequest.Bracket) {
    // engine-managed fallback path
}
```

Expand to accept the new shapes. Pending orders submit and immediately move to `WORKING`; fill events arrive asynchronously via `OnTradeTransaction` and propagate through the existing bus → `OrderManager.onFilled` → `siblings` cancel.

**D. Capability surface** (~30 LOC)

`OrderTypeCapability` enum already exists. Add `STOP`, `LIMIT`, `STOP_LIMIT`, `OCO`, `TRAILING_STOP` values if not already present. MT5Broker declares its supported set; strategies that submit unsupported shapes get a clean compile-time error rather than a runtime `error()`.

**E. Integration tests via `FakeMt5Client`** (~200 LOC)

Cover, for each new shape:
- Submit → wire request asserted (type, price, distance)
- Fill event → `OrderState.FILLED`
- For OCO: leg1 fill → cancel call for leg2's ticket
- For TrailingStop: tick updates → HWM updates → trigger when distance crossed
- Error path: rejected order surfaces a clean failure (not a thrown exception)

**F. Docs**

- `docs/reference/dsl/actions.md` — remove the "Phase 26b" admonitions on `OCO_ENTRY` and pending order types once they work live
- `docs/operations/deploy-docker.md` — add MT5 broker section showing the docker-compose with mt5-gateway + qkt
- `docs/phases/phase-26b-mt5-pending-family.md` (new) — changelog per the §6 template, with worked examples and broker capability matrix
- `docs/planned.md` — remove the Phase 26b entry, since it ships

### Out of scope

**`IfTouched`, `OTO`, `TrailingStopLimit`** — engine supports them but the DSL has no surface. Adding broker translation without a DSL way to reach them is pointless work. Future phase covers DSL + broker together.

**Reconciliation deepening** (Phase 7g hardening) — current state-recovery code (`MT5StateRecovery.kt`) assumes single-position-per-symbol semantics. OCO doesn't violate this (only one leg ever fills) so reconciliation works as-is. If we later want to reconcile *pending* orders (catching a qkt-side restart while MT5 still holds two pending stops), that needs a deeper change — separate phase.

**Bybit native pending support** — Bybit Linear supports pending stops in hedge mode; Bybit Spot does not. Out of scope until a strategy needs it.

**DSL gaps surfaced by Phase 26a** — bare `STOP AT` syntax (without `ORDER_TYPE =` prefix), `STOP_LOSS` single-token form. Real docs-vs-parser gaps but separable cleanup work.

## Architecture

### Translation layer changes

```kotlin
// MT5OrderTranslator.kt — return type changes
fun translate(req: OrderRequest): MT5Translation = when (req) {
    is OrderRequest.Market -> MT5Translation.Single(translateMarket(req))
    is OrderRequest.Bracket -> MT5Translation.Single(translateBracket(req))
    is OrderRequest.Stop -> MT5Translation.Single(translateStop(req))
    is OrderRequest.Limit -> MT5Translation.Single(translateLimit(req))
    is OrderRequest.StopLimit -> MT5Translation.Single(translateStopLimit(req))
    is OrderRequest.TrailingStop -> MT5Translation.Single(translateTrailingStop(req))
    is OrderRequest.StandaloneOCO -> translateStandaloneOCO(req)  // returns Composite
    else -> error("MT5 does not translate ${req::class.simpleName}")
}
```

The new `MT5OrderRequest` wire shape (already exists) accepts `type` strings like `BUY_STOP`, `SELL_LIMIT`, `BUY_STOP_LIMIT`. For trailing, MT5 uses `sl_distance` plus a regular pending or live-position SL — we model this as a Stop with `trail_distance` field populated.

### MT5Broker.submit changes

Today:
```kotlin
override fun submit(request: OrderRequest): SubmitAck {
    if (request !is OrderRequest.Market && request !is OrderRequest.Bracket) {
        return SubmitAck(...)  // not really — actually it errors
    }
    val translation = translator.translate(request)
    client.send(translation)
    ...
}
```

After:
```kotlin
override fun submit(request: OrderRequest): SubmitAck {
    val translation = translator.translate(request)
    when (translation) {
        is MT5Translation.Single -> client.send(translation.request)
        is MT5Translation.Composite -> {
            // Submit each, tag with the same group on the wire
            translation.requests.forEach { client.send(it.copy(group = translation.groupId)) }
        }
    }
    return SubmitAck(request.id, request.id, accepted = true)
}
```

### OrderManager — already done in Phase 26a

`OrderManager.submitOco` at line 693 already populates `siblings[leg1.id] = listOf(leg2.id)` and dispatches both legs. The MT5-side wire submission is what's new.

The `onFilled` handler at line 818 already iterates `siblings[clientOrderId]` and calls `cancel(sibId)` for non-terminal siblings. That cancellation flows through `MT5Broker.cancel` → MT5 wire `OrderCancel` → MT5 broker auto-cancels the sibling ticket.

So the engine-side OCO machinery is fully in place. Phase 26b just needs the MT5 wire translation to keep up.

### Trailing stop on MT5

MT5 supports trailing stops via two mechanisms:
1. **Server-side trailing** — set `sl_distance` parameter on the order. MT5 manages the trail server-side.
2. **Client-side trailing** — qkt's `OrderManager` watches ticks, updates HWM, sends modify requests.

Phase 26b uses (1) for live MT5. The `OrderRequest.TrailingStop` shape carries `trailAmount` and `trailMode`. Translate as:
- `TrailMode.ABSOLUTE` → `sl_distance` in points (MT5's native unit)
- `TrailMode.PERCENT` → compute distance at submit time from current price, then `sl_distance` in points

Note: live MT5 trailing distance is in *points*, not pips. The `MT5BrokerProfile` already has `point` and `pointsPerPip` — translate via these.

### Capability surface

```kotlin
// OrderTypeCapability.kt
enum class OrderTypeCapability {
    MARKET, LIMIT, STOP, STOP_LIMIT, IF_TOUCHED, OCO, TRAILING_STOP, OTO,
}

// MT5Broker.kt
override val supportedOrderTypes: Set<OrderTypeCapability> = setOf(
    OrderTypeCapability.MARKET,
    OrderTypeCapability.LIMIT,
    OrderTypeCapability.STOP,
    OrderTypeCapability.STOP_LIMIT,
    OrderTypeCapability.OCO,
    OrderTypeCapability.TRAILING_STOP,
)
```

Strategies that try to submit an `IF_TOUCHED` or `OTO` order to MT5 get a clean capability-rejection rather than an `error("not translated")`.

## Test plan

### Unit translation tests (`MT5OrderTranslatorTest`)

- `Stop` (BUY, SELL) → `BUY_STOP` / `SELL_STOP` with correct price
- `Limit` (BUY, SELL) → `BUY_LIMIT` / `SELL_LIMIT` with correct price
- `StopLimit` (BUY, SELL) → `BUY_STOP_LIMIT` / `SELL_STOP_LIMIT` with both prices
- `TrailingStop` ABSOLUTE mode → correct `sl_distance` in points
- `TrailingStop` PERCENT mode → correct distance computed from `MarketPriceTracker`
- `StandaloneOCO` → `Composite` with two requests sharing a `groupId`

### Broker integration tests (`MT5BrokerOcoTest`, `MT5BrokerPendingTest`)

Using `FakeMt5Client` (extension of existing test harness):

- Submit pending Stop → MT5 receives one `BUY_STOP` wire request, ticket assigned
- Fill event arrives → `OrderState.FILLED`, `MarketPriceTracker` updated
- Submit OCO → MT5 receives two pending requests with same `group`
- One leg fills → `MT5Broker.cancel` called with the other leg's ticket
- Both legs expire (GTD) → both move to `CANCELLED` without fill events
- Submit TrailingStop → MT5 receives request with `sl_distance` set; modify events arrive as MT5 advances the trail

### End-to-end via existing smoke

`tests/smoke-install.sh` already parses the hedge-straddle example. After Phase 26b lands, add a backtest step that proves an `OCO_ENTRY` strategy generates the expected number of `Signal.Submit(StandaloneOCO)` against a deterministic data slice.

## Migration considerations

**No breaking changes for users.** The `OrderRequest` shapes are unchanged. The translator's internal API changes (`Single` vs `Composite` return) is contained — only `MT5Broker.submit` calls `translate()` today.

**Backtests are unaffected.** PaperBroker continues to handle pending shapes itself; the engine-managed fallback for trailing also continues. Phase 26b only changes the live MT5 path.

**Existing strategies that didn't compile** because they tried to use OCO/pending on MT5 will now compile and run. No existing passing tests need updates.

## Acceptance criteria

- All `MT5OrderTranslator` tests pass.
- All `MT5BrokerOcoTest` and `MT5BrokerPendingTest` tests pass against `FakeMt5Client`.
- A hedge-straddle backtest produces the expected sequence of `Signal.Submit(StandaloneOCO)` events.
- `docs/phases/phase-26b-mt5-pending-family.md` exists with worked examples.
- `docs/reference/dsl/actions.md` no longer says "Phase 26b" for `OCO_ENTRY` capability.
- `tests/smoke-install.sh` runs through `qkt deploy hedge-straddle.qkt` against a live `mt5-gateway` container without errors. (Live MT5 connection — credentials supplied via `MT5_API_URL` env. May skip in CI without credentials; runs locally.)
- The hedge-straddle example README quotes Phase 26b as the "live runtime supported" phase.

## Open questions

1. **`Composite` return type — return value vs. side effect?** Cleanest is a sealed wrapper. Alternative: keep `translate` returning `MT5OrderRequest` but make OCO an explicit `submitOco` method on the broker. Wrapper is more uniform.
2. **Modify-trail vs. submit-modify behavior.** When MT5 trails the SL upward, does it emit an `OrderModified` event we should ingest? Yes — that's how qkt should learn the new SL distance. Confirm during integration tests.
3. **OCO group tagging on MT5.** MT5 native doesn't have a "group" concept — orders are independent tickets. We need our own tagging via the `comment` field or a custom magic-number encoding. `comment` is simpler.
4. **Capability declaration timing.** Should `OrderTypeCapability` checking happen at strategy-compile time or at submit time? Compile-time is friendlier (fails before deployment); submit-time is simpler. Recommend compile-time for known broker; submit-time fallback for unknown.

## References

- `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt:12-20` — current rejection point
- `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt:57` — current submit guard
- `src/main/kotlin/com/qkt/app/OrderManager.kt:693` — `submitOco` (engine-side OCO already wired in Phase 26a)
- `src/main/kotlin/com/qkt/app/OrderManager.kt:818` — sibling cancel-on-fill handler
- Phase 26a worked example: `examples/hedge-straddle/hedge-straddle.qkt`
- pa-quant reference for production OCO behavior: `../fxquant/pa-quant/src/strategies/hedge-straddle/`
- MT5 wire protocol: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`
