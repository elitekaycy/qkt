# Phase 26d — `/orders` + PERCENT + modify · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Three independent MT5 capabilities. Ship PERCENT trailing first (pure qkt-side, no gateway dep). Then `/orders` integration + order modification, both of which depend on mt5-gateway endpoints — implemented defensively so a missing endpoint surfaces as a clean degraded-functionality warning rather than a silent failure.

**Architecture:** PERCENT trailing threads `MarketPriceTracker` from `BrokerFactory` (already provides it) → `MT5Broker` → `MT5OrderTranslator`. `/orders` polling mirrors the `MT5PositionPoller` shape from Phase 26c with a callback for "ticket disappeared." Order modify is a new `MT5Client.modifyOrder` method + `MT5Broker.modify` override.

**Tech stack:** Kotlin (single module), JUnit 5 + AssertJ, MockWebServer for integration. No new dependencies.

> Note: this plan assumes the spec at `docs/superpowers/specs/2026-05-12-phase26d-orders-percent-modify-design.md` was already read. Open the spec alongside this plan when working.

---

### Task 1 — Recon: read MT5 + tracker surface

**Files:**
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt` (template for the new pending poller)
- Read: `src/main/kotlin/com/qkt/marketdata/MarketPriceTracker.kt`
- Read: `src/main/kotlin/com/qkt/broker/Broker.kt` (confirm `modify`, `OrderModification`)
- Read: `src/main/kotlin/com/qkt/events/BrokerEvent.kt` (does `OrderModified` exist?)
- Read: `src/main/kotlin/com/qkt/app/BrokerFactory.kt` (confirm tracker is in scope)

- [ ] **Step 1:** Confirm `MarketPriceTracker.lastPrice(symbol): BigDecimal?` exists. If not, find the equivalent and update plan.
- [ ] **Step 2:** Confirm `BrokerEvent.OrderModified` exists. If not, plan to add it in Task 12.
- [ ] **Step 3:** Confirm `MT5Client.cancelOrder(ticket)` is the existing pattern for ticket-keyed wire calls (will model `modifyOrder` after it).
- [ ] **Step 4:** Baseline: `./gradlew test --no-daemon` green.

---

### Task 2 — PERCENT trailing: AST verification

**Files:**
- Read: `src/main/kotlin/com/qkt/execution/OrderRequest.kt` — confirm `OrderRequest.TrailingStop` has `trailMode: TrailMode` field; `TrailMode` enum has `PERCENT`.
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt:translateTrailingStop` — confirm current PERCENT rejection.

- [ ] **Step 1:** Verify the AST. No change expected; just confirming.

---

### Task 3 — `MT5OrderTranslator` accepts optional MarketPriceTracker

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt`

- [ ] **Step 1:** Add a `priceTracker: MarketPriceTracker? = null` constructor parameter.

```kotlin
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val priceTracker: MarketPriceTracker? = null,
)
```

The `null` default keeps existing tests (which construct the translator directly) backward-compatible.

- [ ] **Step 2:** Implement PERCENT branch in `translateTrailingStop`:

```kotlin
private fun translateTrailingStop(req: OrderRequest.TrailingStop): MT5OrderRequest {
    val point = pointFor(req.symbol)
    val distancePoints = when (req.trailMode) {
        TrailMode.ABSOLUTE -> req.trailAmount.divide(point, MathContext.DECIMAL64)
            .setScale(0, RoundingMode.HALF_UP).toLong()
        TrailMode.PERCENT -> {
            val tracker = priceTracker
                ?: error("PERCENT trailing requires MarketPriceTracker; broker constructor must pass priceTracker")
            val price = tracker.lastPrice(req.symbol)
                ?: error("PERCENT trailing requires lastPrice for ${req.symbol}; tick stream must be active before submit")
            val abs = price.multiply(req.trailAmount).divide(BigDecimal(100), MathContext.DECIMAL64)
            abs.divide(point, MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP).toLong()
        }
    }
    require(distancePoints > 0) { "TrailingStop distance resolves to 0 MT5 points; too tight" }
    return MT5OrderRequest(
        symbol = symbol.toBroker(req.symbol),
        volume = req.quantity,
        type = if (req.side == Side.BUY) "BUY" else "SELL",
        slDistance = distancePoints,
        deviation = profile.deviationPoints,
        magic = profile.magic,
        comment = req.id,
    )
}
```

Removes the existing `require(req.trailMode == TrailMode.ABSOLUTE)` guard.

- [ ] **Step 3:** Add tests.

```kotlin
@Test
fun `TrailingStop PERCENT with priceTracker resolves to absolute slDistance`() {
    val tracker = mockk<MarketPriceTracker>().apply {
        every { lastPrice("EURUSD") } returns BigDecimal("1.1000")
    }
    val profileWithOverride = profile.copy(
        instrumentOverrides = mapOf(
            "EURUSD" to InstrumentSpec(
                minVolume = BigDecimal("0.01"), volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"), digits = 5, tradeStopsLevelPoints = 10,
            ),
        ),
    )
    val translator = MT5OrderTranslator(profileWithOverride, MT5Symbol(profileWithOverride.symbolPolicy), tracker)
    val req = OrderRequest.TrailingStop(
        id = "tr-pct-1", symbol = "EURUSD", side = Side.BUY, quantity = BigDecimal("0.1"),
        trailAmount = BigDecimal("0.5"),  // 0.5% of 1.1000 = 0.0055 = 550 points
        trailMode = TrailMode.PERCENT,
        timeInForce = TimeInForce.GTC, timestamp = 1L,
    )
    val mt5 = (translator.translate(req) as MT5Translation.Single).request
    assertThat(mt5.slDistance).isEqualTo(550L)
}

@Test
fun `PERCENT without priceTracker still rejected with clear error`() {
    val req = OrderRequest.TrailingStop(/* PERCENT */)
    assertThatThrownBy { translator.translate(req) }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("MarketPriceTracker")
}

@Test
fun `PERCENT with priceTracker but no lastPrice for symbol fails actionably`() {
    val tracker = mockk<MarketPriceTracker>().apply { every { lastPrice(any()) } returns null }
    val translator = MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy), tracker)
    assertThatThrownBy { translator.translate(req) }
        .hasMessageContaining("tick stream must be active")
}
```

(If mockk isn't already a test dependency, use an anonymous object implementing `MarketPriceTracker`.)

- [ ] **Step 4:** Run tests, fix until green, commit.

```bash
./gradlew test --tests 'com.qkt.broker.mt5.MT5OrderTranslatorTest' --no-daemon
git add src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt
git commit -m "feat(broker): mt5 PERCENT trailing supported when priceTracker available"
```

---

### Task 4 — Thread MarketPriceTracker through MT5Broker

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Modify: any `BrokerFactory` registration where MT5Broker is constructed

- [ ] **Step 1:** Add `priceTracker: MarketPriceTracker? = null` constructor parameter to `MT5Broker`. Pass it to the translator init.

```kotlin
class MT5Broker(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val priceTracker: MarketPriceTracker? = null,
    private val client: MT5Client = MT5Client(/* ... */),
) : Broker {
    private val translator = MT5OrderTranslator(profile, mt5Symbol, priceTracker)
    /* ... */
}
```

- [ ] **Step 2:** Update `DaemonCommand.kt:65-72` where MT5Broker is constructed in the factory. The factory signature already provides `MarketPriceTracker`:

```kotlin
val brokerFactories: Map<String, com.qkt.app.BrokerFactory> =
    mt5Profiles.associate { profile ->
        profile.name.lowercase() to
            { bus, clock, tracker ->
                com.qkt.broker.mt5.MT5Broker(profile, bus, clock, tracker)
            }
    }
```

- [ ] **Step 3:** Build + existing tests green.

```bash
./gradlew test --tests 'com.qkt.broker.mt5.*' --no-daemon
```

- [ ] **Step 4:** Commit.

```bash
git commit -am "feat(broker): plumb MarketPriceTracker through MT5Broker to translator"
```

---

### Task 5 — `MT5PendingOrder` wire type + `MT5Client.getPendingOrders`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt` (new test methods)

- [ ] **Step 1:** Add `MT5PendingOrder` data class.

```kotlin
/** Pending (working) order reported by the gateway. Distinct from [MT5Position] which is filled. */
data class MT5PendingOrder(
    val ticket: Long,
    val symbol: String,
    val type: String,  // "BUY_STOP", "SELL_LIMIT", etc.
    val volume: BigDecimal,
    val priceOpen: BigDecimal,
    val sl: BigDecimal,
    val tp: BigDecimal,
    val magic: Int,
    val timeSetup: Long,
    val timeExpiration: Long,
    val comment: String? = null,
)
```

- [ ] **Step 2:** Add `MT5Client.getPendingOrders(magic: Int?): List<MT5PendingOrder>`. Pattern after `getPositions`. Endpoint: `GET /orders?magic=X`.

```kotlin
fun getPendingOrders(magic: Int? = null): List<MT5PendingOrder> {
    val url = if (magic != null) "$gatewayUrl/orders?magic=$magic" else "$gatewayUrl/orders"
    val raw = getWithRetry(url) ?: return emptyList()
    val arr = json.parseToJsonElement(raw).jsonArray
    return arr.map { parsePendingOrder(it.jsonObject) }
}

private fun parsePendingOrder(obj: JsonObject): MT5PendingOrder {
    val rawTime = obj["time_setup"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    val rawExp = obj["time_expiration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    return MT5PendingOrder(
        ticket = obj["ticket"]!!.jsonPrimitive.content.toLong(),
        symbol = obj["symbol"]!!.jsonPrimitive.content,
        type = obj["type"]!!.jsonPrimitive.content,
        volume = obj["volume"]!!.jsonPrimitive.content.toBigDecimal(),
        priceOpen = obj["price_open"]!!.jsonPrimitive.content.toBigDecimal(),
        sl = obj["sl"]?.jsonPrimitive?.contentOrNull?.toBigDecimal() ?: BigDecimal.ZERO,
        tp = obj["tp"]?.jsonPrimitive?.contentOrNull?.toBigDecimal() ?: BigDecimal.ZERO,
        magic = obj["magic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        timeSetup = rawTime - tzOffsetMs,
        timeExpiration = rawExp - tzOffsetMs,
        comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
    )
}
```

- [ ] **Step 3:** Tests for the client method using `MockWebServer`. Assert wire-shape parsing.

- [ ] **Step 4:** If mt5-gateway doesn't expose `/orders` (returns 404), `getWithRetry` returns `null`, `getPendingOrders` returns empty list. Defensive — no exception. The Phase 26d poller below treats "always empty" as "no pendings to track" which is degraded but safe.

- [ ] **Step 5:** Run, commit.

```bash
git commit -am "feat(broker): mt5 getPendingOrders client method and wire type"
```

---

### Task 6 — `MT5PendingOrderPoller`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5PendingOrderPoller.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5PendingOrderPollerTest.kt` (new)

- [ ] **Step 1:** Implement the poller. Mirror `MT5PositionPoller`'s structure.

```kotlin
class MT5PendingOrderPoller(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val onPendingDisappeared: ((Long) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(MT5PendingOrderPoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastSnapshot: Map<Long, MT5PendingOrder> = emptyMap()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastSnapshot = client.getPendingOrders(magic = profile.magic).associateBy { it.ticket }
        thread = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(profile.pollIntervalMs)
                    if (!running.get()) break
                    tick()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    log.warn("MT5 pending-order poller for ${profile.name} tick failed", e)
                }
            }
        }, "qkt-mt5-pending-poller-${profile.name}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    private fun tick() {
        val current = client.getPendingOrders(magic = profile.magic).associateBy { it.ticket }
        val disappeared = lastSnapshot.keys - current.keys
        for (ticket in disappeared) {
            onPendingDisappeared?.invoke(ticket)
        }
        lastSnapshot = current
    }
}
```

- [ ] **Step 2:** Tests for: seed snapshot at start, tick emits disappeared, stop is idempotent.

- [ ] **Step 3:** Commit.

```bash
git commit -am "feat(broker): mt5 pending-order poller with disappear callback"
```

---

### Task 7 — `MT5Broker` integrates the pending poller with TTL-cached fill detection

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`

The spec calls out a race condition between the position poller and the pending-order poller. When a pending fills:
- Position poller (Phase 26c) sees the new position → emits `OrderFilled`
- Pending poller (this phase) sees the ticket disappear from `/orders` → should NOT emit `OrderCancelled`

When a pending is externally cancelled or GTD-expires:
- Position poller sees nothing
- Pending poller sees the ticket disappear → should emit `OrderCancelled`

The disambiguator: did a position with the same ticket open recently? Implementation: track recently-filled tickets with a TTL.

- [ ] **Step 1:** Add to `MT5Broker`:

```kotlin
private val recentlyFilledTickets: MutableMap<Long, Long> = ConcurrentHashMap()  // ticket → fillTimeMs

private fun onPendingPositionOpened(position: MT5Position) {
    val meta = pendingByTicket.remove(position.ticket) ?: return
    pendingTickets.remove(meta.orderId)
    recentlyFilledTickets[position.ticket] = clock.now()  // mark as filled
    bus.publish(BrokerEvent.OrderFilled(/* ... existing Phase 26c ... */))
}

private fun onPendingDisappeared(ticket: Long) {
    val meta = pendingByTicket.remove(ticket) ?: return  // not ours, ignore
    pendingTickets.entries.removeIf { it.value == ticket }
    val ttlMs = profile.pollIntervalMs * 3
    val recentlyFilled = recentlyFilledTickets[ticket]?.let { fillTime ->
        clock.now() - fillTime < ttlMs
    } ?: false
    if (recentlyFilled) {
        recentlyFilledTickets.remove(ticket)  // consume the marker
        return  // already emitted OrderFilled via the position-poller path
    }
    // Truly cancelled (external or GTD)
    bus.publish(BrokerEvent.OrderCancelled(
        clientOrderId = meta.orderId,
        brokerOrderId = ticket.toString(),
        reason = "external or gtd-expired (pending disappeared without fill)",
        strategyId = meta.strategyId,
        timestamp = clock.now(),
    ))
}
```

- [ ] **Step 2:** Wire `MT5PendingOrderPoller` into `MT5Broker.init`:

```kotlin
private val pendingPoller = MT5PendingOrderPoller(
    client, profile,
    onPendingDisappeared = ::onPendingDisappeared,
)

init {
    /* existing position poller start */
    pendingPoller.start()
}

fun shutdown() {
    poller.stop()
    pendingPoller.stop()
}
```

- [ ] **Step 3:** Integration test for the race: enqueue a placeOrder response, then on the next position poll return the matching position, on the same tick the pending poll returns empty. Assert: ONE `OrderFilled`, ZERO `OrderCancelled`.

- [ ] **Step 4:** Integration test for external cancel: enqueue placeOrder response, then on next pending poll return empty (and position poll continues empty). Wait > TTL. Assert: `OrderCancelled` with reason "external or gtd-expired".

- [ ] **Step 5:** Run, commit.

```bash
git commit -am "feat(broker): mt5 pending poller with TTL-cached fill-vs-cancel resolution"
```

---

### Task 8 — Order modification — wire types + client method

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`

- [ ] **Step 1:** Add `MT5OrderModification`:

```kotlin
data class MT5OrderModification(
    val price: BigDecimal? = null,
    val sl: BigDecimal? = null,
    val tp: BigDecimal? = null,
    val slDistance: Long? = null,
    val expiration: Long? = null,
)
```

- [ ] **Step 2:** Add `MT5Client.modifyOrder(ticket: Long, mods: MT5OrderModification): MT5OrderResponse`. Pattern after `placeOrder`. Endpoint: assume `POST /modify-order/{ticket}` or `PUT /order/{ticket}` — check gateway docs.

```kotlin
fun modifyOrder(ticket: Long, mods: MT5OrderModification): MT5OrderResponse {
    val body = encodeModification(mods).toRequestBody(JSON_MEDIA)
    val request = Request.Builder()
        .url("$gatewayUrl/modify-order/$ticket")
        .post(body)
        .build()
    val resp = http.newCall(request).execute()
    resp.use {
        val raw = it.body?.string().orEmpty()
        if (!it.isSuccessful) {
            return MT5OrderResponse(
                result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                errorMessage = "HTTP ${it.code}: $raw",
            )
        }
        return parseOrderResponse(raw)
    }
}

private fun encodeModification(m: MT5OrderModification): String {
    val sb = StringBuilder("{")
    val fields = mutableListOf<String>()
    if (m.price != null) fields += "\"price\":${m.price.toPlainString()}"
    if (m.sl != null) fields += "\"sl\":${m.sl.toPlainString()}"
    if (m.tp != null) fields += "\"tp\":${m.tp.toPlainString()}"
    if (m.slDistance != null) fields += "\"sl_distance\":${m.slDistance}"
    if (m.expiration != null) fields += "\"expiration\":${m.expiration}"
    sb.append(fields.joinToString(","))
    sb.append("}")
    return sb.toString()
}
```

- [ ] **Step 3:** Client unit test asserts wire shape and parses response.

- [ ] **Step 4:** Commit.

```bash
git commit -am "feat(broker): mt5 modifyOrder client method and wire type"
```

---

### Task 9 — `MT5Broker.modify` override

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`

- [ ] **Step 1:** Override `Broker.modify`:

```kotlin
override fun modify(orderId: String, changes: OrderModification): SubmitAck {
    val ticket = pendingTickets[orderId]
        ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "modify: no pending order $orderId")
    val mt5Mods = MT5OrderModification(
        price = changes.newStopPrice ?: changes.newLimitPrice,
        sl = null,  // OrderModification doesn't carry SL today
        tp = null,
    )
    val resp = client.modifyOrder(ticket, mt5Mods)
    return if (isOrderSuccessful(resp.result.retcode)) {
        bus.publish(BrokerEvent.OrderModified(
            clientOrderId = orderId,
            brokerOrderId = ticket.toString(),
            strategyId = "",
            timestamp = clock.now(),
        ))
        SubmitAck(orderId, ticket.toString(), accepted = true)
    } else {
        reject(/* synthesize from orderId since we don't have a full OrderRequest here */)
    }
}
```

- [ ] **Step 2:** If `BrokerEvent.OrderModified` doesn't exist yet, add it (sibling of `OrderCancelled`, `OrderFilled`).

- [ ] **Step 3:** Integration test: place pending → modify with newStopPrice → mock server receives POST/PUT with correct body.

- [ ] **Step 4:** Test: modify with unknown orderId returns rejected SubmitAck without calling the client.

- [ ] **Step 5:** Run, commit.

```bash
git commit -am "feat(broker): mt5 modify override with order-id to ticket lookup"
```

---

### Task 10 — Capability — MODIFY in MT5Protocol

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt`

- [ ] **Step 1:** Add `MODIFY` to the protocol's declared capabilities:

```kotlin
val capabilities: Set<OrderTypeCapability> = setOf(
    /* existing */,
    OrderTypeCapability.MODIFY,
)
```

- [ ] **Step 2:** Confirm `OrderTypeCapability.MODIFY` already exists (per `Broker.kt` reading — yes).

- [ ] **Step 3:** Commit.

```bash
git commit -am "feat(broker): mt5 protocol declares MODIFY capability"
```

---

### Task 11 — Phase 26d changelog + docs cleanup

**Files:**
- Create: `docs/phases/phase-26d-orders-percent-modify.md`
- Modify: `docs/planned.md` (remove 26d entry)
- Modify: `docs/phases/index.md` (add 26d link)
- Modify: `examples/hedge-straddle/README.md` (note 26d caveats removed)

- [ ] **Step 1:** Write the changelog per §6 of the qkt skill: Summary, What's new, Migration, Usage cookbook, Testing patterns, Known limitations, References.

Worked examples:
- PERCENT trailing strategy
- Pending order with GTD expiry that gets caught by the new poller
- Modify a pending order's trigger price

- [ ] **Step 2:** Cross-reference Phase 26b/c changelogs noting that pending fill-vs-cancel disambiguation now uses the dedicated poller.

- [ ] **Step 3:** Commit.

```bash
git commit -am "docs(phases): phase 26d changelog and planned.md cleanup"
```

---

### Task 12 — Pre-merge verification

- [ ] **Step 1:** `./gradlew build --no-daemon` — BUILD SUCCESSFUL incl. ktlint
- [ ] **Step 2:** `bash tests/smoke-install.sh` — green
- [ ] **Step 3:** Read every commit message; confirm conventions.
- [ ] **Step 4:** `git push -u origin phase-26d-impl`
- [ ] **Step 5:** Open PR with §5 PR template body.

---

## Self-review checklist

- [ ] Spec sub-feature A (`/orders` poller with TTL-cached fill detection) covered by Tasks 5-7
- [ ] Spec sub-feature B (PERCENT trailing) covered by Tasks 2-4
- [ ] Spec sub-feature C (Order modify) covered by Tasks 8-10
- [ ] No placeholders; every step has concrete code or a concrete verification
- [ ] Race-condition test (fill-vs-cancel) explicitly covered in Task 7
- [ ] Defensive degradation (gateway 404 → empty/rejected, not exception) explicitly noted in Task 5 Step 4
- [ ] mt5-gateway endpoint dependency clearly documented per sub-feature
