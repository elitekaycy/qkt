# Phase 7h — Bybit Derivatives + Rate Limiting

**Status:** Design draft.
**Predecessor:** Phase 7g (periodic reconciliation + balance polling).
**Successor:** Phase 8 (PnL tagging) — first phase that needs `strategyId` attribution.

---

## 1. Mission

Ship the second Bybit broker product, hardening the transport layer along the way. After this phase, the engine can trade Bybit USDT perpetuals (`BYBIT_LINEAR:BTCUSDT`) alongside spot, with broker-authoritative position reconciliation, reactive rate-limit handling, and paginated execution recovery. The `BrokerStateRecovery` interface naturally falls out — we now have two impls, so the abstraction is observed rather than designed.

This phase deliberately does NOT ship inverse perpetuals (low volume, base-coin margin complicates math), account state polling (equity/margin — paired with Phase 8 PnL), or hedge mode (one-way is sufficient for non-institutional flow).

---

## 2. Goals

- `BybitLinearBroker` — mirrors `BybitSpotBroker` shape, uses shared `BybitClient` with `category="linear"`. Symbol prefix `BYBIT_LINEAR:`.
- `BybitLinearStateRecovery` — orders + executions + balances + **positions** reconcile. Implements new `BrokerStateRecovery` interface.
- `BybitSpotStateRecovery` — renamed from `BybitStateRecovery`. Implements same interface.
- `BrokerStateRecovery` interface in `com.qkt.broker` — `fun reconcile()`. Minimal.
- `BrokerEvent.PositionReconciled` — new variant for position drift events. Carries old/new qty + avgPx + source + reason.
- `PositionTracker.reset(symbol, qty, avgPx)` — broker-authoritative reset. Subscribes to `PositionReconciled` events.
- Reactive 429 handling in `BybitClient.postSigned` — parse `X-Bapi-Limit-Reset-Timestamp`, sleep until reset (cap 5s), retry once. Still 429 → `BybitRateLimitException`.
- Pagination on `/v5/execution/list` — follow `nextPageCursor` until empty or 200-record cap.
- Configurable `BYBIT_ACCOUNT_TYPE` env var (default `UNIFIED`) — fixes 7g hardcode.
- `BybitOrderTranslator` accepts `category` param — `"spot"` or `"linear"`. Linear-specific fields default sanely (`positionIdx=0`, `reduceOnly` opt-in).
- Backward compatible at the engine level: existing `BybitSpotBroker`, `CompositeBroker`, and strategy code compile unchanged. Composite picks up `BYBIT_LINEAR:` automatically via prefix routing.

## Non-goals

- **No inverse perpetuals** (`BYBIT_INVERSE:`). Base-coin margin (BTC margin for BTCUSD) requires per-coin position math; defer until needed.
- **No account state polling.** Equity, margin level, free margin, total unrealized PnL — all derivatives concerns that pair with Phase 8 PnL framework.
- **No hedge mode.** One-way mode (`positionIdx=0`) only. Hedge mode adds long+short concurrent positions per symbol, which mismatches `PositionTracker`'s single-position-per-symbol shape. Future phase if needed.
- **No pre-emptive rate-limit token bucket.** Reactive 429 handling is sufficient for current scale (single-strategy, single-broker traffic). Pre-emptive bucketing requires per-endpoint quota tracking and is over-engineering until measured pressure exists.
- **No retry budget for `reconcileExecutions` pagination loop failures.** If the cursor follow fails mid-loop, log and use what we got. Next periodic reconcile catches the rest.
- **No spot position reconciliation.** Same reasoning as 7g — pair-net engine view doesn't map to per-coin wallet balances. Linear is the only product where reconciliation is honest.
- **No leverage / margin mode configuration.** Bybit defaults apply (cross margin, isolated set per-symbol via Bybit UI). Future phase.
- **No funding rate tracking.** Periodic funding payments / receipts on perpetuals — informational, can be added later.

---

## 3. Background — current state (Phase 7g, post-merge)

```kotlin
class BybitStateRecovery(...)         // spot-only; orders + executions + balances

class BybitSpotBroker(transport, bus, clock, recoveryWindowMs, pollIntervalMs, pollExecutor)
    init {
        recovery = BybitStateRecovery(...)
        transport.onReconnect { recovery.reconcile() }
        recovery.reconcile()           // initial sync
        reconciler = PeriodicReconciler(intervalMs, { recovery.reconcile() })
        reconciler.start()
    }

class BybitClient(...) : BybitTransport
    fun postSigned(path, body) {
        // 3-attempt retry on connection failures
        // BybitApiException on retCode != 0 (no retry)
    }

interface BybitTransport {
    val isConnected: Boolean
    val balances: Map<String, BigDecimal>
    fun updateBalances(snapshot)
    fun onReconnect(handler)
    fun postSigned(path, body): String
    fun subscribe(topic, listener)
    fun onDisconnect(handler)
}

class PositionTracker {
    fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal
    fun apply(trade): BigDecimal       // delta-based
    fun positionFor(symbol): Position?
    fun allPositions(): Map<...>
    // No reset() — currently fill-only
}

interface CompositeBroker {
    // Routes by SymbolPattern.prefix("BYBIT_SPOT:") → BybitSpotBroker
    // Adding BYBIT_LINEAR:* is one route addition
}
```

Limitations forcing this phase:

- **No derivatives broker.** Engine can't trade USDT perpetuals — the largest product on Bybit by volume.
- **No proper position reconciliation.** 7g shipped balance polling but explicitly deferred position reconcile because spot pair-net ≠ wallet coin balance. Linear has clean directional positions; reconcile is now tractable.
- **429 errors propagate as `OrderRejected`.** A burst of submissions during low-quota windows kills orders that would succeed if rate-limited. Reactive 429 handling fixes this.
- **`/v5/execution/list` is single-page (50 records).** Pathological WS gaps with >50 fills lose the oldest. Pagination closes the gap.
- **Hardcoded `accountType=UNIFIED`** in `BybitStateRecovery`. Live accounts using legacy `CONTRACT` or `SPOT` types get empty wallet responses with no override.
- **`BybitStateRecovery` is spot-only by name.** Reusing for linear requires either rename or a sibling class. We need both — different reconcile shapes.

---

## 4. Architecture overview

Three additions to existing layers:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Layer A — generic, broker-agnostic                                  │
│                                                                       │
│  com.qkt.broker.BrokerStateRecovery (NEW)                            │
│    interface { fun reconcile() }                                     │
│                                                                       │
│  com.qkt.events.BrokerEvent.PositionReconciled (NEW)                 │
│    data class (symbol, oldQty?, newQty, oldAvgPx?, newAvgPx,         │
│                source, reason, ts, seq)                              │
│                                                                       │
│  com.qkt.positions.PositionTracker.reset (NEW method)                │
│    fun reset(symbol, qty, avgPx)                                     │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  Layer B — Bybit-specific                                            │
│                                                                       │
│  BybitClient (extended)                                               │
│    + 429 reactive handling in postSigned                             │
│    + BybitRateLimitException                                         │
│                                                                       │
│  BybitOrderTranslator (extended)                                      │
│    + category param ("spot" | "linear")                              │
│    + linear-specific fields (positionIdx=0, reduceOnly)              │
│                                                                       │
│  BybitSpotStateRecovery (renamed from BybitStateRecovery)            │
│    : BrokerStateRecovery                                             │
│    paths: orders, executions (paginated), balances                   │
│                                                                       │
│  BybitLinearStateRecovery (NEW)                                      │
│    : BrokerStateRecovery                                             │
│    paths: orders, executions (paginated), balances, positions       │
│                                                                       │
│  BybitLinearBroker (NEW)                                              │
│    mirrors BybitSpotBroker shape                                     │
│    uses BybitLinearStateRecovery                                     │
│    supports("BYBIT_LINEAR:...") = true                               │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

**The composite layer is unchanged.** Routing by `SymbolPattern.prefix("BYBIT_LINEAR:")` is a one-line addition at construction site. No core engine changes.

---

## 5. `BrokerStateRecovery` interface

### Definition

```kotlin
package com.qkt.broker

interface BrokerStateRecovery {
    fun reconcile()
}
```

That's the entire interface. Single method, no return type, no parameters.

### Why so minimal

The recovery class internalizes everything it needs: transport reference, bus, clock, known-orders provider, lastFillTime provider, seenExecIds set, etc. Externally, recovery is invoked from two places:
1. `transport.onReconnect { recovery.reconcile() }` — WS bounce trigger (7f).
2. `PeriodicReconciler(action = { recovery.reconcile() })` — periodic timer (7g).

Both call sites need only `() -> Unit`. The interface formalizes that contract without leaking internals.

### Why not a parameterized contract

Tempting to design `interface BrokerStateRecovery { fun reconcileOrders(); fun reconcileExecutions(); fun reconcilePositions() }` with each path as a separate method. **Rejected.** Different brokers will have different reconcile shapes (spot has no positions; linear does; futures might add funding events; FX might add lot-size reconciliation). Making the interface enumerate paths forces every impl to define every method or throw. The single-method `reconcile()` lets each impl orchestrate its own paths internally.

This matches the principle from CLAUDE.md §11: trust internal code, validate at boundaries. The boundary here is "the periodic poll triggers reconcile"; what reconcile does internally is the broker's concern.

---

## 6. `BybitOrderTranslator` — category parameter

### Current shape (7g)

```kotlin
object BybitOrderTranslator {
    fun toCreateBody(request: OrderRequest): String { /* hardcoded "category":"spot" */ }
    fun toCancelBody(symbol: String, orderLinkId: String): String { /* hardcoded "spot" */ }
    fun parseExecution(json): ParsedExecution
    fun parseOpenOrder(json): ParsedOpenOrder
}
```

### New shape

```kotlin
object BybitOrderTranslator {
    fun toCreateBody(
        request: OrderRequest,
        category: String,                 // "spot" | "linear"
        positionIdx: Int = 0,             // 0=one-way, 1=hedge-buy, 2=hedge-sell
        reduceOnly: Boolean = false,
    ): String

    fun toCancelBody(
        symbol: String,
        orderLinkId: String,
        category: String,
    ): String

    fun toAmendBody(...): String          // already exists; gains category

    fun parseExecution(json): ParsedExecution    // unchanged; symbol parsing is generic
    fun parseOpenOrder(json): ParsedOpenOrder    // unchanged
}
```

### Linear-specific JSON additions

Linear order create body adds:
```json
{
  "category": "linear",
  "symbol": "BTCUSDT",
  "side": "Buy",
  "orderType": "Market",
  "qty": "0.001",
  "positionIdx": 0,           // one-way mode
  "reduceOnly": false,        // closing-only flag
  // ... rest same as spot
}
```

For one-way mode with `reduceOnly=false`, default values match Bybit's defaults — but explicit is clearer in logs and matches the API spec exactly.

### Migration

`BybitSpotBroker.submit` calls `toCreateBody(request, category = "spot")`. `BybitLinearBroker.submit` calls `toCreateBody(request, category = "linear")`. Both get sane defaults. No call-site signature breakage for external users — the `category` param is required, but only Bybit code calls it.

---

## 7. Rate-limit (429) handling

### Bybit's rate-limit model

- **IP-based**: 600 req / 5s (general).
- **UID-based**: per-endpoint variable (create order: 10/s; cancel: 10/s; query positions: 50/s).
- Headers per response:
  - `X-Bapi-Limit-Status` — remaining quota in current window.
  - `X-Bapi-Limit` — max quota.
  - `X-Bapi-Limit-Reset-Timestamp` — epoch ms when window resets.
- HTTP 429 response when exceeded, with same headers populated.

### Approach: reactive only

```kotlin
fun postSigned(path: String, body: String): String {
    var attempts = 0
    while (attempts < MAX_ATTEMPTS) {           // existing 3-attempt retry on conn failure
        try {
            val response = doPostSigned(path, body)
            if (response.code == 429) {
                val resetAt = parseLimitReset(response)
                val sleepMs = (resetAt - clock.now()).coerceIn(0, MAX_429_SLEEP_MS)
                if (sleepMs > MAX_429_SLEEP_MS) {
                    throw BybitRateLimitException("Rate limit; reset in ${sleepMs}ms (cap ${MAX_429_SLEEP_MS}ms)")
                }
                Thread.sleep(sleepMs)
                attempts++
                continue
            }
            return parseBody(response)          // throws BybitApiException on retCode != 0
        } catch (e: IOException) {
            // existing connection-failure retry path
        }
    }
    throw BybitRateLimitException("Rate limit not cleared after $attempts attempts")
}
```

Key design choices:

- **Cap sleep at 5 seconds.** A retry-after longer than that means the caller's thread blocks too long; throw and let the strategy decide. 5s is empirically a generous cap; Bybit's 5-second window resets in ≤5s by definition.
- **One retry per 429.** If still 429 after the wait, throw. We don't loop indefinitely.
- **Independent from connection-failure retry.** The 3-attempt retry is for transport errors (IOException, 5xx). 429 is a separate condition. Both can stack: a transient 5xx during a 429-recovery wait still gets the connection retry.
- **`BybitRateLimitException` is its own type.** Strategies catching it specifically can back off; generic catches still fall to OrderRejected.
- **Synchronous sleep is acceptable.** No coroutines; the engine is single-threaded by design (Phase 1 invariant). Periodic reconcile runs on a daemon executor anyway, so its block-time is its own concern.

### Why not pre-emptive token bucket

A predictive bucket tracks remaining quota client-side: `if (remainingQuota < threshold) wait`. Sounds robust but adds:
- Per-endpoint quota state (Bybit has dozens of endpoints with different limits).
- Synchronization across threads (multiple brokers, periodic reconciler, strategy submits).
- Drift handling (server-side counter ≠ client-side counter).
- Configuration surface (when to back off, how to recover).

For the current scale (single-engine, ~6 reconcile calls/min, occasional submits), the reactive approach is sufficient. We're using ~5% of quota in steady state; 429 is genuinely an edge case. Add pre-emptive bucketing only when measurement shows pressure.

---

## 8. Pagination on `/v5/execution/list`

### Bybit response shape

```json
{
  "retCode": 0,
  "result": {
    "list": [/* up to 50 entries */],
    "nextPageCursor": "abc123..."         // empty string when exhausted
  }
}
```

### Implementation

```kotlin
private fun reconcileExecutions() {
    val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
    var cursor = ""
    var totalProcessed = 0

    while (totalProcessed < MAX_EXECUTIONS_PER_RECONCILE) {        // 200 cap
        val body = buildExecutionListBody(startTime, cursor)
        val response = transport.postSigned("/v5/execution/list", body)
        val tree = json.parseToJsonElement(response).jsonObject
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return

        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return
        for (entry in list) {
            // existing per-execution logic (parseExecution, dedup, publish OrderFilled)
            totalProcessed++
        }

        cursor = tree["result"]?.jsonObject?.get("nextPageCursor")?.jsonPrimitive?.content ?: ""
        if (cursor.isEmpty() || list.isEmpty()) break
    }
}
```

### Constants

- `MAX_EXECUTIONS_PER_RECONCILE = 200` — caps a single reconcile to 4 pages × 50 records. Pathological gaps (e.g., >200 fills during a 5-minute outage) are rare in practice; if encountered, the next reconcile picks up where this one left off via `lastFillTime` advancement.
- Page size hardcoded at Bybit's default 50; no user-facing tuning.

### Edge cases

- **Empty `nextPageCursor`** → loop exits cleanly.
- **REST failure mid-loop** → existing connection retry handles it. If still failing after 3 attempts, the loop throws; recovery's outer `runCatching` logs and the next periodic tick retries.
- **`lastFillTime` advances during loop** → harmless; we use the snapshot from start.

---

## 9. `BybitLinearStateRecovery` — position reconciliation

### Position data shape (Bybit `/v5/position/list`)

```json
{
  "retCode": 0,
  "result": {
    "list": [{
      "symbol": "BTCUSDT",
      "side": "Buy",                     // "Buy" = long, "Sell" = short, "" = flat
      "size": "0.5",                     // signed by side semantically; absolute in this field
      "avgPrice": "80000",
      "positionValue": "40000",
      "leverage": "10",
      "unrealisedPnl": "100.5",
      "category": "linear",
      "positionIdx": 0
    }]
  }
}
```

### Reconcile flow

```kotlin
private fun reconcilePositions() {
    val response = transport.postSigned(
        "/v5/position/list",
        """{"category":"linear","settleCoin":"USDT"}"""
    )
    val tree = json.parseToJsonElement(response).jsonObject
    if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
    val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return

    val brokerPositions: Map<String, BrokerPosition> = parsePositions(list)

    // 1. For each broker position, compare to engine's view
    for ((bareSymbol, bp) in brokerPositions) {
        val qktSymbol = "BYBIT_LINEAR:$bareSymbol"
        val enginePos = positionProvider.positionFor(qktSymbol)

        val drift =
            enginePos == null
                || enginePos.quantity.compareTo(bp.signedQty) != 0
                || enginePos.avgEntryPrice.compareTo(bp.avgPrice) != 0

        if (drift) {
            bus.publish(
                BrokerEvent.PositionReconciled(
                    symbol = qktSymbol,
                    oldQty = enginePos?.quantity,
                    newQty = bp.signedQty,
                    oldAvgPx = enginePos?.avgEntryPrice,
                    newAvgPx = bp.avgPrice,
                    source = "BYBIT_LINEAR",
                    reason = "periodic reconcile",
                    timestamp = clock.now(),
                )
            )
        }
    }

    // 2. For each engine position the broker doesn't report → flat (closed externally)
    val brokerSymbols = brokerPositions.keys.map { "BYBIT_LINEAR:$it" }.toSet()
    for ((sym, pos) in positionProvider.allPositions()) {
        if (sym.startsWith("BYBIT_LINEAR:") && sym !in brokerSymbols && pos.quantity.signum() != 0) {
            bus.publish(
                BrokerEvent.PositionReconciled(
                    symbol = sym,
                    oldQty = pos.quantity,
                    newQty = BigDecimal.ZERO,
                    oldAvgPx = pos.avgEntryPrice,
                    newAvgPx = BigDecimal.ZERO,
                    source = "BYBIT_LINEAR",
                    reason = "broker reports flat (externally closed)",
                    timestamp = clock.now(),
                )
            )
        }
    }
}
```

### Tolerance threshold

```kotlin
private fun BigDecimal.differsFrom(other: BigDecimal, tolerance: BigDecimal): Boolean =
    (this - other).abs() > tolerance

private val POSITION_TOLERANCE = BigDecimal("1e-8")
```

Avoids spurious events from float-style rounding. Configurable via constructor param `positionTolerance: BigDecimal = BigDecimal("1e-8")`.

### Sign convention

Bybit returns `size` as absolute value with `side="Buy"|"Sell"` indicator. Engine `PositionTracker` uses signed quantity (positive long, negative short). Translator: `size = if (side == "Sell") -size else size`. Done at parse time so engine sees engine-native shape.

---

## 10. `BrokerEvent.PositionReconciled` event

```kotlin
sealed interface BrokerEvent : Event {
    // ... existing variants ...

    data class PositionReconciled(
        val symbol: String,                        // "BYBIT_LINEAR:BTCUSDT"
        val oldQty: BigDecimal?,                   // null = engine had no position
        val newQty: BigDecimal,                    // signed (positive long, negative short)
        val oldAvgPx: BigDecimal?,                 // null = engine had no position
        val newAvgPx: BigDecimal,                  // 0 if newQty=0
        val source: String,                        // "BYBIT_LINEAR" — broker prefix attribution
        val reason: String,                        // "periodic reconcile", "broker reports flat", etc.
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent
}
```

### Subscribers

```kotlin
// PositionTracker subscribes once at construction:
bus.subscribe<BrokerEvent.PositionReconciled> { event ->
    reset(event.symbol, event.newQty, event.newAvgPx)
}

// Strategies / risk / observability can subscribe too:
bus.subscribe<BrokerEvent.PositionReconciled> { event ->
    if (event.oldQty != null && event.oldQty.compareTo(event.newQty) != 0) {
        log.warn("Position drift detected: {} {} → {} (reason: {})",
            event.symbol, event.oldQty, event.newQty, event.reason)
    }
}
```

### EventBus exhaustive-match update

Same pattern as 7g `BalancesUpdated`:

```kotlin
when (event) {
    // ... existing ...
    is BrokerEvent.PositionReconciled -> event.copy(timestamp = ts, sequenceId = seq)
}
```

---

## 11. `PositionTracker.reset(symbol, qty, avgPx)`

### New method

```kotlin
class PositionTracker : PositionProvider {
    // ... existing ...

    fun reset(
        symbol: String,
        qty: BigDecimal,
        avgPx: BigDecimal,
    ) {
        if (qty.signum() == 0) {
            positions.remove(symbol)
        } else {
            positions[symbol] = Position(symbol, qty, avgPx)
        }
    }
}
```

### Bus wiring (engine setup)

`PositionTracker` does not subscribe to bus by default — it's a passive component, fed by application-layer code. The application wires the subscription:

```kotlin
val positions = PositionTracker()
bus.subscribe<BrokerEvent.OrderFilled> { e -> positions.applyFill(e) }   // existing pattern
bus.subscribe<BrokerEvent.PositionReconciled> { e ->                     // NEW
    positions.reset(e.symbol, e.newQty, e.newAvgPx)
}
```

This keeps `PositionTracker` decoupled from bus internals (matches Phase 1 invariant: components don't know about each other).

### Race: reset during applyFill

If a fill arrives at the same time as a reconcile, both modify the position. Two cases:

- **Reconcile then fill**: reset to (10, $80k); fill of +1 at $81k applies on top. Result: (11, weighted avg). Correct — reset is the new baseline.
- **Fill then reconcile**: fill of +1 brings engine to (11, weighted); reconcile says (10, $80k). Reset overwrites. Engine state matches broker — but a fill we processed is now "lost" from the position view (still in the events log). This is OK because broker is the source of truth; if Bybit says (10, $80k) it's the right answer.

Pathological: fill arrives between broker's REST snapshot and reset — engine was correct, reset makes it briefly wrong. Next reconcile (30s later) fixes. Bounded staleness, acceptable.

---

## 12. `BybitLinearBroker`

### Constructor

```kotlin
class BybitLinearBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val positionProvider: PositionProvider,    // NEW: needed for reconciliation
    private val recoveryWindowMs: Long = 5 * 60_000L,
    private val pollIntervalMs: Long = 30_000L,
    pollExecutor: ScheduledExecutorService? = null,
) : Broker
```

`positionProvider` is new — `BybitSpotBroker` doesn't need it because spot has no per-symbol position reconcile. Linear's recovery diffs against the provider.

### Capabilities

```kotlin
override val capabilities = setOf(
    OrderTypeCapability.MARKET,
    OrderTypeCapability.LIMIT,
    OrderTypeCapability.STOP,
    OrderTypeCapability.STOP_LIMIT,
    OrderTypeCapability.IF_TOUCHED,
    OrderTypeCapability.MODIFY,
)
```

Identical to `BybitSpotBroker`. Linear additionally supports `REDUCE_ONLY` and `POST_ONLY` flags but those aren't in our capability enum yet — Phase 8+ work.

### `supports(symbol)`

```kotlin
override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_LINEAR:")
```

### Submit / cancel / modify

Mirrors `BybitSpotBroker` line-for-line, with `category="linear"` everywhere. The linear-specific fields (`positionIdx=0`, `reduceOnly=false`) are translator concerns, not broker concerns.

### Init block

```kotlin
init {
    transport.subscribe("order") { frame -> onOrderFrame(frame) }
    transport.subscribe("execution") { frame -> onExecutionFrame(frame) }

    val recovery = BybitLinearStateRecovery(
        transport, bus, clock,
        positionProvider = positionProvider,
        getKnownOrders = { knownOrders.toMap() },
        lastFillTimeProvider = { lastFillTime.get() },
        seenExecIds = seenExecIds,
    )
    transport.onReconnect { recovery.reconcile() }
    recovery.reconcile()                            // initial sync

    reconciler = PeriodicReconciler(
        intervalMs = pollIntervalMs,
        action = { recovery.reconcile() },
        executor = pollExecutor ?: defaultExecutor(),
    )
    reconciler.start()

    // Pruning subscriptions (same as spot)
    bus.subscribe<BrokerEvent.OrderFilled> { e -> prune(e.clientOrderId) }
    bus.subscribe<BrokerEvent.OrderCancelled> { e -> prune(e.clientOrderId) }
    bus.subscribe<BrokerEvent.OrderRejected> { e -> prune(e.clientOrderId) }
}
```

---

## 13. Configurable `accountType`

### Env var

```bash
BYBIT_ACCOUNT_TYPE=UNIFIED     # default
BYBIT_ACCOUNT_TYPE=CONTRACT    # legacy contract account
BYBIT_ACCOUNT_TYPE=SPOT        # legacy spot account
```

### Implementation

`BybitClient` constructor:

```kotlin
class BybitClient(
    // ...
    accountType: String? = null,
) : BybitTransport {
    val resolvedAccountType: String =
        accountType
            ?: System.getenv("BYBIT_ACCOUNT_TYPE")
            ?: "UNIFIED"
}
```

Recovery reads it via transport (new property):

```kotlin
interface BybitTransport {
    val accountType: String       // NEW
    // ...
}
```

`BybitStateRecovery.reconcileBalances()`:

```kotlin
val response = transport.postSigned(
    "/v5/account/wallet-balance",
    """{"accountType":"${transport.accountType}"}"""
)
```

`FakeBybitClient` defaults `accountType = "UNIFIED"`.

---

## 14. Race conditions and edge cases

### Race-1: position reconcile fires during a submit

Submit at T=0 returns OrderAccepted. Bybit fills at T=50ms; WS exec arrives at T=100ms. Reconcile at T=80ms hits `/v5/position/list` which does NOT yet show the fill (broker-side propagation lag). Reconcile sees (0 qty); engine sees (1 qty from fill); diff event resets engine to 0. Then WS exec arrives, applies +1, engine back to 1.

**Resolution:** acceptable; engine briefly disagrees with broker for ~50ms. No financial impact (no decisions made in that window). Tolerance threshold + `lastFillTime` window prevent spurious resets in the common case.

**Stronger fix (deferred):** suppress reconcile if any fill arrived in last 100ms. Adds complexity; defer until profiling shows pressure.

### Race-2: 429 retry-after exceeds 5s cap

Bybit returns 429 with `Reset-Timestamp` 7 seconds out. We throw `BybitRateLimitException` rather than block 7s. Strategy receives `OrderRejected` (via the broker's catch-all). Strategy decides whether to retry.

### Race-3: pagination cursor returns same data twice

Bybit's cursor model is supposed to be deterministic. If a bug causes duplicate executions across pages, our `seenExecIds` dedup catches it. No double-fill.

### Race-4: PositionTracker.reset called during applyFill

JVM-level: `applyFill` is single-threaded (called from bus subscriber on EventBus thread). `reset` is called from the same subscriber chain (via PositionReconciled handler). Both run on the bus's serial dispatch. No race.

### Race-5: linear broker init triggers reconcile before strategy subscriptions land

`BybitLinearBroker.<init>` calls `recovery.reconcile()` synchronously. If recovery emits `PositionReconciled` and PositionTracker hasn't subscribed yet, the event is dropped.

**Resolution:** application setup order is `PositionTracker subscribe → BybitLinearBroker construct`. Documented in changelog. Same pattern as 7g.

### Race-6: account flips from Unified to Standard mid-session

Operator changes account type in Bybit UI. Our hardcoded value goes stale. Reconcile starts returning empty balances/positions silently.

**Resolution:** Phase 7h doesn't detect this. Document as operational concern. Future phase: parse `accountType` mismatch from response and re-resolve.

### Edge: `BYBIT_LINEAR:BTCUSDT` traded by both linear and spot brokers

If application accidentally registers both with the same symbol pattern, `CompositeBroker` routes to the first match. Spot won't accept linear symbols (`supports()` returns false), so submit returns rejection. No silent double-routing.

### Edge: fully reduced position (newQty = 0)

`PositionTracker.reset(symbol, 0, 0)` removes the entry from the map. `positionFor(symbol)` returns null thereafter. Strategies querying must handle null (already do).

---

## 15. Multi-broker scaling

The architecture extends to N brokers without refactor:

1. **`BrokerStateRecovery` interface** — new brokers implement this. Same poll/reconcile flow.
2. **Symbol prefix continues to attribute.** `BYBIT_LINEAR:BTCUSDT` is a string; routing via composite is the contract.
3. **`PositionReconciled.source`** — broker prefix carries attribution. Multi-broker risk components group by source.
4. **`BybitLinearBroker` is the template.** Future `AlpacaFuturesBroker`, `IBKRFuturesBroker`, etc. mirror its shape.
5. **Rate-limit handling** — currently Bybit-specific (`X-Bapi-Limit-*` headers). When second venue lands, the venue brings its own rate-limit shape; pull common pattern out only if shape repeats.

---

## 16. Configuration

### Constructor parameters

`BybitLinearBroker`:

| Parameter | Default | Purpose |
|---|---|---|
| `recoveryWindowMs` | `5 * 60_000` | Same as spot |
| `pollIntervalMs` | `30_000` | Same as spot |
| `pollExecutor` | `null` (default executor) | Test seam |
| `positionProvider` | (required) | For reconcile diff |

### Environment variables

| Var | Default | Purpose |
|---|---|---|
| `BYBIT_API_KEY` / `BYBIT_API_SECRET` | (required) | 7e |
| `BYBIT_TESTNET` | `true` | 7e |
| `BYBIT_ACCOUNT_TYPE` | `UNIFIED` | 7h — fixes 7g hardcode |

### Constants (internal, not user-facing)

| Constant | Value | Where |
|---|---|---|
| `MAX_429_SLEEP_MS` | `5_000L` | `BybitClient.postSigned` |
| `MAX_EXECUTIONS_PER_RECONCILE` | `200` | `*StateRecovery.reconcileExecutions` |
| `POSITION_TOLERANCE` | `1e-8` | `BybitLinearStateRecovery.reconcilePositions` |

---

## 17. Testing approach

### Unit tests

| Class | Scope |
|---|---|
| `BybitOrderTranslatorCategoryTest` | `category="linear"` produces correct JSON; `positionIdx=0`, `reduceOnly=false` defaults |
| `BybitClientRateLimitTest` | 429 → sleep until reset → retry; cap-exceeded throws; 429 followed by 200 succeeds |
| `BybitStateRecoveryPaginationTest` | Multi-page execution list with `nextPageCursor`; cap at 200 records |
| `BybitLinearStateRecoveryTest` | Position reconcile: drift detection, sign convention (Sell → negative), flat external close, tolerance threshold |
| `BybitLinearBrokerTest` | Submit/cancel/modify mirrors spot tests with `category="linear"` |
| `BybitLinearBrokerReconcilerIntegrationTest` | Initial reconcile + periodic ticks + close pattern |
| `PositionTrackerResetTest` | `reset(symbol, qty, avgPx)` overwrites; reset-to-zero removes entry; bus subscriber wiring |
| `BrokerEventTest` | `PositionReconciled` round-trips through bus |

### Fakes

`FakeBybitClient` gains:
- `override val accountType: String = "UNIFIED"` (overridable per test)
- Programmable response for `/v5/position/list`
- Helper to build paginated `/v5/execution/list` responses with `nextPageCursor` chain
- 429 simulation: response queue with HTTP code + headers

### `e2e-live`

`BybitLinearLiveSmokeTest` (`@Tag("e2e-live")`):
- Submit `OrderRequest.Limit` for `BYBIT_LINEAR:BTCUSDT` at $1 (far from market).
- Assert `OrderAccepted`.
- Cancel.
- Verify `client.balances` populated.
- Verify `PositionTracker.positionFor("BYBIT_LINEAR:BTCUSDT")` either null (no fill) or matches reconcile.

Skipped without credentials.

---

## 18. Migration

### From Phase 7g → 7h

| 7g | 7h | Notes |
|---|---|---|
| `BybitStateRecovery` | renamed to `BybitSpotStateRecovery` | Implements new `BrokerStateRecovery` interface. |
| `BybitOrderTranslator.toCreateBody(request)` | `toCreateBody(request, category, positionIdx?, reduceOnly?)` | New required `category` param. Internal callers (only Bybit code) updated. |
| `BybitTransport` had 7 members | gains `accountType: String` | `FakeBybitClient` defaults to `"UNIFIED"`. |
| `BybitClient.postSigned` retried on conn failures only | now also handles 429 | Backwards compatible (existing successful calls unaffected). |
| `BrokerEvent` had 6 variants | gains `PositionReconciled` | Existing subscribers unaffected. |
| `PositionTracker` was fill-only | gains `reset(symbol, qty, avgPx)` | Application wires `bus.subscribe<PositionReconciled> { tracker.reset(...) }`. |
| `/v5/execution/list` was single-page | now paginated with cap | Transparent to callers. |
| `accountType=UNIFIED` hardcoded | configurable via `BYBIT_ACCOUNT_TYPE` | Default unchanged. |

### Application setup change

```kotlin
// 7g
val positions = PositionTracker()
bus.subscribe<BrokerEvent.OrderFilled> { e -> positions.applyFill(e) }
val spot = BybitSpotBroker(client, bus, clock)

// 7h
val positions = PositionTracker()
bus.subscribe<BrokerEvent.OrderFilled> { e -> positions.applyFill(e) }
bus.subscribe<BrokerEvent.PositionReconciled> { e -> positions.reset(e.symbol, e.newQty, e.newAvgPx) }   // NEW
val spot = BybitSpotBroker(client, bus, clock)
val linear = BybitLinearBroker(client, bus, clock, positions)                                              // NEW

val composite = CompositeBroker(
    routes = listOf(
        SymbolPattern.prefix("BYBIT_SPOT:")   to spot,
        SymbolPattern.prefix("BYBIT_LINEAR:") to linear,                                                    // NEW
    ),
    fallback = paperBroker,
    bus = bus,
)
```

No engine, strategy, or DSL surface changes.

---

## 19. Out of scope (deferred)

| Feature | Phase | Rationale |
|---|---|---|
| Inverse perpetuals (`BYBIT_INVERSE:`) | future | Low volume, base-coin margin complications. |
| Account state (equity, margin level, free margin, total uPnL) | 8 | Pairs with PnL framework. |
| Hedge mode (long+short concurrent) | future | Mismatches `PositionTracker` single-position model. |
| Pre-emptive token-bucket rate limiting | future | Reactive sufficient until measured pressure. |
| Funding rate tracking | future | Periodic perpetual cashflows; informational. |
| Leverage / margin mode configuration | future | Bybit defaults apply. |
| `OrderTypeCapability.REDUCE_ONLY`, `POST_ONLY` | future | Engine doesn't dispatch on these flags yet. |
| `PositionReconciled` from spot side | (never as designed) | Spot pair-net ≠ wallet shape; rejected in 7g. |
| JVM-restart persistence | future | Same as 7f/7g. |
| Multi-account | future | Same as 7g. |

---

## 20. Summary

Phase 7h ships USDT perpetuals via `BybitLinearBroker`, hardens transport with reactive 429 handling and pagination, extracts the `BrokerStateRecovery` interface that we now have evidence for, and delivers the broker-authoritative position reconciliation that 7g deliberately deferred. Spot reconciliation stays as 7g shipped it (orders + executions + balances); linear adds a fourth path (positions) where the math is honest.

Surface area is moderate — one new broker (mirroring spot), one new event variant, one new `PositionTracker` method, one renamed class, one extracted interface, three transport hardenings (429, pagination, accountType). Risk is bounded by mirroring 7e/7f/7g patterns. After 7h, the broker layer covers spot + USDT perpetuals with full resilience + reconciliation; the missing piece for "live ready" becomes PnL attribution, which is Phase 8.
