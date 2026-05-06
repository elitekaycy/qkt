# Phase 7h — Bybit Derivatives + Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `BybitLinearBroker` for USDT perpetuals, broker-authoritative position reconciliation, reactive 429 handling, paginated execution recovery, configurable account type, and an extracted `BrokerStateRecovery` interface.

**Architecture:** New broker mirrors `BybitSpotBroker`. Shared client uses `category="linear"`. Position reconcile diffs `/v5/position/list` against `PositionTracker`, emits `PositionReconciled`, resets tracker. Reactive 429 handling parses Bybit reset header. Recovery interface formalizes the `reconcile()` contract.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ, OkHttp, kotlinx-serialization-json, SLF4J.

**Spec:** [`../specs/2026-05-06-trading-engine-phase7h-design.md`](../specs/2026-05-06-trading-engine-phase7h-design.md)

---

## Pre-flight

Branch already exists: `phase7h-derivatives-and-rate-limit`. Spec already committed.

```bash
git status                       # clean (or only ?? tt.txt)
git branch --show-current        # phase7h-derivatives-and-rate-limit
./gradlew check                  # green from main
```

---

## Task 1: Extract `BrokerStateRecovery` interface and rename to `BybitSpotStateRecovery`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/BrokerStateRecovery.kt`
- Rename: `src/main/kotlin/com/qkt/broker/bybit/BybitStateRecovery.kt` → `BybitSpotStateRecovery.kt` (also rename class)
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt` (use renamed type)
- Rename: `src/test/kotlin/com/qkt/broker/bybit/BybitStateRecoveryTest.kt` → `BybitSpotStateRecoveryTest.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/qkt/broker/BrokerStateRecovery.kt
package com.qkt.broker

interface BrokerStateRecovery {
    fun reconcile()
}
```

- [ ] **Step 2: Rename file and class**

```bash
git mv src/main/kotlin/com/qkt/broker/bybit/BybitStateRecovery.kt \
       src/main/kotlin/com/qkt/broker/bybit/BybitSpotStateRecovery.kt
git mv src/test/kotlin/com/qkt/broker/bybit/BybitStateRecoveryTest.kt \
       src/test/kotlin/com/qkt/broker/bybit/BybitSpotStateRecoveryTest.kt
```

In the renamed source file, replace `class BybitStateRecovery` with `class BybitSpotStateRecovery : com.qkt.broker.BrokerStateRecovery`. Add `import com.qkt.broker.BrokerStateRecovery` at the top.

In the renamed test file, replace `class BybitStateRecoveryTest` with `class BybitSpotStateRecoveryTest` and `BybitStateRecovery(` constructor calls with `BybitSpotStateRecovery(`.

- [ ] **Step 3: Update `BybitSpotBroker` to use renamed type**

In `BybitSpotBroker.kt`, find the recovery construction and replace `BybitStateRecovery(...)` with `BybitSpotStateRecovery(...)`. Also update any field type or import.

```bash
grep -n 'BybitStateRecovery' src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt
# fix each occurrence
```

Also update `BybitStateRecovery.ManagedOrderView` references to `BybitSpotStateRecovery.ManagedOrderView`.

- [ ] **Step 4: Run full test suite to verify no regression**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL. All ~548 tests pass. The rename + interface addition is purely structural.

- [ ] **Step 5: ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(broker): extract BrokerStateRecovery interface and rename to BybitSpotStateRecovery"
```

---

## Task 2: `BybitOrderTranslator` accepts `category` parameter

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt`
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt` (call sites)
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitOrderTranslatorCategoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitOrderTranslatorCategoryTest.kt
package com.qkt.broker.bybit

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitOrderTranslatorCategoryTest {
    private fun marketRequest(symbol: String) =
        OrderRequest.Market(
            id = "c1",
            symbol = symbol,
            side = Side.BUY,
            quantity = Money.of("0.01"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    @Test
    fun `linear category sets category=linear and includes positionIdx 0`() {
        val body = BybitOrderTranslator.toCreateBody(marketRequest("BYBIT_LINEAR:BTCUSDT"), category = "linear")

        assertThat(body).contains("\"category\":\"linear\"")
        assertThat(body).contains("\"positionIdx\":0")
    }

    @Test
    fun `linear category includes reduceOnly flag when set`() {
        val body =
            BybitOrderTranslator.toCreateBody(
                marketRequest("BYBIT_LINEAR:BTCUSDT"),
                category = "linear",
                reduceOnly = true,
            )

        assertThat(body).contains("\"reduceOnly\":true")
    }

    @Test
    fun `spot category does not include positionIdx or reduceOnly`() {
        val body = BybitOrderTranslator.toCreateBody(marketRequest("BYBIT_SPOT:BTCUSDT"), category = "spot")

        assertThat(body).contains("\"category\":\"spot\"")
        assertThat(body).doesNotContain("positionIdx")
        assertThat(body).doesNotContain("reduceOnly")
    }

    @Test
    fun `cancel body uses given category`() {
        val body = BybitOrderTranslator.toCancelBody(symbol = "BYBIT_LINEAR:BTCUSDT", orderLinkId = "c1", category = "linear")
        assertThat(body).contains("\"category\":\"linear\"")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitOrderTranslatorCategoryTest`
Expected: FAIL — `category` param doesn't exist on `toCreateBody`.

- [ ] **Step 3: Modify `BybitOrderTranslator`**

Open `src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt`. Locate `toCreateBody(request)` and `toCancelBody(symbol, orderLinkId)`. Add `category` parameter to both, and the linear-specific fields:

```kotlin
fun toCreateBody(
    request: OrderRequest,
    category: String,
    positionIdx: Int = 0,
    reduceOnly: Boolean = false,
): String {
    // ... existing field assembly ...
    // Replace "\"category\":\"spot\"" with "\"category\":\"$category\""
    // After the main fields, conditionally append:
    // if (category == "linear") {
    //     sb.append(",\"positionIdx\":$positionIdx")
    //     if (reduceOnly) sb.append(",\"reduceOnly\":true")
    // }
}

fun toCancelBody(
    symbol: String,
    orderLinkId: String,
    category: String,
): String {
    // Replace hardcoded "spot" with $category
}
```

If a `toAmendBody` exists (modify path), apply the same `category` parameter pattern.

- [ ] **Step 4: Update `BybitSpotBroker` call sites**

In `BybitSpotBroker.submit`, change `toCreateBody(request)` to `toCreateBody(request, category = "spot")`.

In `BybitSpotBroker.cancel`, change `toCancelBody(symbol = symbol, orderLinkId = orderId)` to `toCancelBody(symbol = symbol, orderLinkId = orderId, category = "spot")`.

If `BybitSpotBroker.modify` calls `toAmendBody`, add `category = "spot"` there too.

- [ ] **Step 5: Run all bybit tests**

Run: `./gradlew test --tests 'com.qkt.broker.bybit.*'`
Expected: PASS. Existing spot tests should compile and pass since they go through `BybitSpotBroker`.

- [ ] **Step 6: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt \
        src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitOrderTranslatorCategoryTest.kt
git commit -m "feat(broker): add category parameter to BybitOrderTranslator with linear-specific fields"
```

---

## Task 3: Pagination on `/v5/execution/list`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotStateRecovery.kt`
- Modify: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotStateRecoveryTest.kt`

- [ ] **Step 1: Write failing test**

Append to `BybitSpotStateRecoveryTest.kt`:

```kotlin
@Test
fun `reconcile follows nextPageCursor across multiple execution-list pages`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
    client.responses["/v5/account/wallet-balance"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
    client.responses["/v5/execution/list"] =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"a","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}],"nextPageCursor":"page2"}}"""
    client.responsesByPredicate.add(
        Pair(
            { path, body -> path == "/v5/execution/list" && body.contains("\"cursor\":\"page2\"") },
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c2","orderId":"b","symbol":"BTCUSDT","side":"Sell","execPrice":"81000","execQty":"0.01","execId":"e2","category":"spot"}],"nextPageCursor":""}}""",
        ),
    )

    val bus = newBus()
    val fills = mutableListOf<BrokerEvent.OrderFilled>()
    bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }

    val recovery =
        BybitSpotStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        )
    recovery.reconcile()

    assertThat(fills).hasSize(2)
    assertThat(fills.map { it.clientOrderId }).containsExactly("c1", "c2")
}

@Test
fun `reconcile caps pagination at 200 records to bound memory`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
    client.responses["/v5/account/wallet-balance"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    // Each page returns 50 records and a non-empty cursor — would loop forever without cap
    val pageJson = StringBuilder("""{"retCode":0,"retMsg":"OK","result":{"list":[""")
    for (i in 1..50) {
        if (i > 1) pageJson.append(",")
        pageJson.append("""{"orderLinkId":"c$i","orderId":"a$i","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e-${System.nanoTime()}-$i","category":"spot"}""")
    }
    pageJson.append("""],"nextPageCursor":"more"}}""")

    client.responses["/v5/execution/list"] = pageJson.toString()

    val bus = newBus()
    val fills = mutableListOf<BrokerEvent.OrderFilled>()
    bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }

    val recovery =
        BybitSpotStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        )
    recovery.reconcile()

    assertThat(fills.size).isLessThanOrEqualTo(200)
}
```

`FakeBybitClient` doesn't currently support predicate-based responses. We need to add that capability — see step 2.

- [ ] **Step 2: Extend `FakeBybitClient` with predicate-based responses**

In `src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt`:

```kotlin
val responsesByPredicate: MutableList<Pair<(String, String) -> Boolean, String>> = mutableListOf()

override fun postSigned(
    path: String,
    jsonBody: String,
): String {
    posts.add(Posted(path, jsonBody))
    val matched = responsesByPredicate.firstOrNull { it.first(path, jsonBody) }
    if (matched != null) return matched.second
    return responses[path] ?: """{"retCode":0,"retMsg":"OK","result":{}}"""
}
```

Replaces the existing `postSigned` body.

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew test --tests 'com.qkt.broker.bybit.BybitSpotStateRecoveryTest.reconcile follows nextPageCursor across multiple execution-list pages'`
Expected: FAIL — current implementation makes one call only; no pagination loop.

- [ ] **Step 4: Implement pagination**

In `BybitSpotStateRecovery.reconcileExecutions`:

```kotlin
private fun reconcileExecutions() {
    val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
    var cursor = ""
    var totalProcessed = 0
    val cap = 200

    while (totalProcessed < cap) {
        val body =
            if (cursor.isEmpty()) {
                """{"category":"spot","startTime":$startTime,"limit":50}"""
            } else {
                """{"category":"spot","startTime":$startTime,"limit":50,"cursor":"$cursor"}"""
            }
        val response = transport.postSigned("/v5/execution/list", body)
        val tree = json.parseToJsonElement(response).jsonObject
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return

        for (entry in list) {
            val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
            if (!seenExecIds.add(exec.execId)) continue
            val qktSymbol = BybitSymbol.toQkt(category = "spot", bare = exec.bareSymbol)
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = exec.clientOrderId,
                    brokerOrderId = exec.brokerOrderId,
                    symbol = qktSymbol,
                    side = exec.side,
                    price = exec.price,
                    quantity = exec.quantity,
                    timestamp = clock.now(),
                ),
            )
            totalProcessed++
            if (totalProcessed >= cap) return
        }

        cursor = tree["result"]?.jsonObject?.get("nextPageCursor")?.jsonPrimitive?.content ?: ""
        if (cursor.isEmpty() || list.isEmpty()) break
    }
}
```

- [ ] **Step 5: Verify tests pass**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitSpotStateRecoveryTest`
Expected: PASS, all tests including the two new pagination tests.

- [ ] **Step 6: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/broker/bybit/BybitSpotStateRecovery.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitSpotStateRecoveryTest.kt \
        src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt
git commit -m "feat(broker): paginate /v5/execution/list with 200-record cap"
```

---

## Task 4: Reactive 429 handling + `accountType` configuration

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt`
- Modify: `src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitClientRateLimitTest.kt`
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotStateRecovery.kt` (use transport.accountType)

- [ ] **Step 1: Write failing test for 429 handling**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitClientRateLimitTest.kt
package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitClientRateLimitTest {
    private fun makeClient(httpClient: OkHttpClient): BybitClient =
        BybitClient(
            apiKey = "k",
            apiSecret = "s",
            testnet = true,
            httpClient = httpClient,
            clock = FixedClock(0L),
        )

    @Test
    fun `429 then 200 succeeds with sleep cap respected`() {
        val attempts = AtomicInteger()
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val n = attempts.incrementAndGet()
                    if (n == 1) {
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(429)
                            .message("Too Many Requests")
                            .header("X-Bapi-Limit-Reset-Timestamp", "100")
                            .body("""{"retCode":10006,"retMsg":"rate limit"}""".toResponseBody())
                            .build()
                    } else {
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"retCode":0,"retMsg":"OK","result":{}}""".toResponseBody())
                            .build()
                    }
                }.build()
        val client = makeClient(httpClient)

        val response = client.postSigned("/v5/order/create", """{}""")

        assertThat(response).contains("\"retCode\":0")
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `429 with reset beyond cap throws BybitRateLimitException`() {
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("X-Bapi-Limit-Reset-Timestamp", "10000")  // 10s out — exceeds 5s cap
                        .body("""{"retCode":10006,"retMsg":"rate limit"}""".toResponseBody())
                        .build()
                }.build()
        val client = makeClient(httpClient)

        assertThatThrownBy { client.postSigned("/v5/order/create", """{}""") }
            .isInstanceOf(BybitRateLimitException::class.java)
    }

    @Test
    fun `persistent 429 after one retry throws BybitRateLimitException`() {
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("X-Bapi-Limit-Reset-Timestamp", "10")  // tiny — sleep is bounded
                        .body("""{"retCode":10006,"retMsg":"rate limit"}""".toResponseBody())
                        .build()
                }.build()
        val client = makeClient(httpClient)

        assertThatThrownBy { client.postSigned("/v5/order/create", """{}""") }
            .isInstanceOf(BybitRateLimitException::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitClientRateLimitTest`
Expected: FAIL — `BybitRateLimitException` doesn't exist; 429 currently propagates unrecognized.

- [ ] **Step 3: Add `BybitRateLimitException` and `accountType` to client**

In `BybitClient.kt`, near `BybitConnectException`:

```kotlin
class BybitRateLimitException(
    message: String,
) : RuntimeException(message)
```

In `BybitTransport` interface, add:

```kotlin
val accountType: String
```

In `BybitClient` constructor, add `accountType: String? = null` parameter and resolve:

```kotlin
private val resolvedAccountType: String =
    accountType
        ?: System.getenv("BYBIT_ACCOUNT_TYPE")
        ?: "UNIFIED"

override val accountType: String get() = resolvedAccountType
```

- [ ] **Step 4: Modify `postSigned` to handle 429**

Find `postSigned` in `BybitClient.kt`. Wrap the existing 3-attempt retry loop with 429 detection:

```kotlin
override fun postSigned(path: String, jsonBody: String): String {
    val MAX_429_SLEEP_MS = 5_000L
    var rateLimitRetried = false
    var connectionAttempts = 0
    val maxConnectionAttempts = 3

    while (true) {
        try {
            val response = doRawPost(path, jsonBody)            // returns the OkHttp Response
            if (response.code == 429) {
                if (rateLimitRetried) {
                    response.close()
                    throw BybitRateLimitException("Rate limit not cleared after retry; path=$path")
                }
                val resetHeader = response.header("X-Bapi-Limit-Reset-Timestamp")?.toLongOrNull() ?: 0L
                val sleepMs = (resetHeader - clock.now()).coerceIn(0, MAX_429_SLEEP_MS + 1)
                response.close()
                if (sleepMs > MAX_429_SLEEP_MS) {
                    throw BybitRateLimitException("Rate limit reset in ${sleepMs}ms exceeds cap ${MAX_429_SLEEP_MS}ms; path=$path")
                }
                Thread.sleep(sleepMs)
                rateLimitRetried = true
                continue
            }
            val body = response.use { it.body?.string() ?: "" }
            return parseAndValidate(body)                       // existing retCode validation, throws BybitApiException
        } catch (e: java.io.IOException) {
            connectionAttempts++
            if (connectionAttempts >= maxConnectionAttempts) throw e
            Thread.sleep(connectionBackoff(connectionAttempts))
        }
    }
}
```

(Adapt to existing `postSigned` shape. The current implementation likely already separates HTTP exec from body parsing — just inject the 429 check between them.)

- [ ] **Step 5: Update `FakeBybitClient` with `accountType`**

```kotlin
override var accountType: String = "UNIFIED"
```

- [ ] **Step 6: Update `BybitSpotStateRecovery.reconcileBalances` to use transport.accountType**

Replace:
```kotlin
val response = transport.postSigned("/v5/account/wallet-balance", """{"accountType":"UNIFIED"}""")
```
With:
```kotlin
val response = transport.postSigned(
    "/v5/account/wallet-balance",
    """{"accountType":"${transport.accountType}"}"""
)
```

Update the existing test `reconcile sends accountType UNIFIED in wallet-balance request body` if needed (it asserts on hardcoded UNIFIED, which still passes since FakeBybitClient defaults to UNIFIED).

- [ ] **Step 7: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt \
        src/main/kotlin/com/qkt/broker/bybit/BybitSpotStateRecovery.kt \
        src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitClientRateLimitTest.kt
git commit -m "feat(broker): add reactive 429 handling and configurable accountType"
```

---

## Task 5: `PositionTracker.reset(symbol, qty, avgPx)`

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/PositionProvider.kt`
- Create: `src/test/kotlin/com/qkt/positions/PositionTrackerResetTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// src/test/kotlin/com/qkt/positions/PositionTrackerResetTest.kt
package com.qkt.positions

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionTrackerResetTest {
    @Test
    fun `reset overwrites existing position with new qty and avgPx`() {
        val tracker = PositionTracker()
        tracker.reset("BTCUSDT", BigDecimal("0.5"), BigDecimal("80000"))

        val position = tracker.positionFor("BTCUSDT")

        assertThat(position?.quantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(position?.avgEntryPrice).isEqualByComparingTo(BigDecimal("80000"))
    }

    @Test
    fun `reset to zero quantity removes the entry`() {
        val tracker = PositionTracker()
        tracker.reset("BTCUSDT", BigDecimal("0.5"), BigDecimal("80000"))
        tracker.reset("BTCUSDT", BigDecimal.ZERO, BigDecimal.ZERO)

        assertThat(tracker.positionFor("BTCUSDT")).isNull()
    }

    @Test
    fun `reset accepts negative quantity for short positions`() {
        val tracker = PositionTracker()
        tracker.reset("BTCUSDT", BigDecimal("-0.5"), BigDecimal("80000"))

        assertThat(tracker.positionFor("BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("-0.5"))
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests com.qkt.positions.PositionTrackerResetTest`
Expected: FAIL — `reset` method doesn't exist.

- [ ] **Step 3: Add `reset` method to `PositionTracker`**

In `src/main/kotlin/com/qkt/positions/PositionProvider.kt`, inside `class PositionTracker`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew test --tests com.qkt.positions.PositionTrackerResetTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/positions/PositionProvider.kt \
        src/test/kotlin/com/qkt/positions/PositionTrackerResetTest.kt
git commit -m "feat(positions): add PositionTracker reset for broker-authoritative resync"
```

---

## Task 6: `BrokerEvent.PositionReconciled` event

**Files:**
- Modify: `src/main/kotlin/com/qkt/events/BrokerEvent.kt`
- Modify: `src/main/kotlin/com/qkt/bus/EventBus.kt` (exhaustive when)
- Modify: `src/test/kotlin/com/qkt/events/BrokerEventTest.kt`

- [ ] **Step 1: Add `PositionReconciled` variant to `BrokerEvent`**

In `BrokerEvent.kt`, add after `BalancesUpdated`:

```kotlin
data class PositionReconciled(
    val symbol: String,
    val oldQty: BigDecimal?,
    val newQty: BigDecimal,
    val oldAvgPx: BigDecimal?,
    val newAvgPx: BigDecimal,
    val source: String,
    val reason: String,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : BrokerEvent
```

- [ ] **Step 2: Add to `EventBus` exhaustive when**

In `EventBus.kt`, after the `BalancesUpdated` line:

```kotlin
is BrokerEvent.PositionReconciled -> event.copy(timestamp = ts, sequenceId = seq)
```

- [ ] **Step 3: Add round-trip test**

Append to `BrokerEventTest.kt`:

```kotlin
@Test
fun `PositionReconciled round-trips through the EventBus`() {
    val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    val received = mutableListOf<BrokerEvent.PositionReconciled>()
    bus.subscribe<BrokerEvent.PositionReconciled> { received.add(it) }

    bus.publish(
        BrokerEvent.PositionReconciled(
            symbol = "BYBIT_LINEAR:BTCUSDT",
            oldQty = BigDecimal("0.4"),
            newQty = BigDecimal("0.5"),
            oldAvgPx = BigDecimal("79000"),
            newAvgPx = BigDecimal("80000"),
            source = "BYBIT_LINEAR",
            reason = "periodic reconcile",
        ),
    )

    assertThat(received).hasSize(1)
    assertThat(received.single().symbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
    assertThat(received.single().oldQty).isEqualByComparingTo(BigDecimal("0.4"))
}

@Test
fun `PositionReconciled allows null old fields for new positions`() {
    val event =
        BrokerEvent.PositionReconciled(
            symbol = "BYBIT_LINEAR:ETHUSDT",
            oldQty = null,
            newQty = BigDecimal("1.0"),
            oldAvgPx = null,
            newAvgPx = BigDecimal("3000"),
            source = "BYBIT_LINEAR",
            reason = "broker reports new position",
        )

    assertThat(event.oldQty).isNull()
    assertThat(event.oldAvgPx).isNull()
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.qkt.events.BrokerEventTest`
Expected: PASS, including new tests.

- [ ] **Step 5: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/events/BrokerEvent.kt \
        src/main/kotlin/com/qkt/bus/EventBus.kt \
        src/test/kotlin/com/qkt/events/BrokerEventTest.kt
git commit -m "feat(events): add BrokerEvent PositionReconciled variant"
```

---

## Task 7: `BybitLinearStateRecovery`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitLinearStateRecovery.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitLinearStateRecoveryTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitLinearStateRecoveryTest.kt
package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitLinearStateRecoveryTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private val emptyOk = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun seedAllEmpty(client: FakeBybitClient) {
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] = emptyOk
    }

    private class FixedPositionProvider(private val map: Map<String, Position>) : PositionProvider {
        override fun positionFor(symbol: String): Position? = map[symbol]

        override fun allPositions(): Map<String, Position> = map
    }

    @Test
    fun `reconcile emits PositionReconciled when broker has a position engine doesn't know`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Buy","size":"0.5","avgPrice":"80000","category":"linear"}]}}"""

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        val recovery =
            BybitLinearStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(0L),
                positionProvider = FixedPositionProvider(emptyMap()),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(events).hasSize(1)
        val e = events.single()
        assertThat(e.symbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
        assertThat(e.oldQty).isNull()
        assertThat(e.newQty).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(e.newAvgPx).isEqualByComparingTo(BigDecimal("80000"))
        assertThat(e.source).isEqualTo("BYBIT_LINEAR")
    }

    @Test
    fun `reconcile applies sign convention - Sell side becomes negative qty`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Sell","size":"0.3","avgPrice":"80000","category":"linear"}]}}"""

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(emptyMap()),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events.single().newQty).isEqualByComparingTo(BigDecimal("-0.3"))
    }

    @Test
    fun `reconcile emits flat event for engine positions broker no longer reports`() {
        val client = FakeBybitClient()
        seedAllEmpty(client)

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        val enginePositions =
            mapOf(
                "BYBIT_LINEAR:BTCUSDT" to Position("BYBIT_LINEAR:BTCUSDT", BigDecimal("0.5"), BigDecimal("80000")),
            )

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(enginePositions),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events).hasSize(1)
        assertThat(events.single().newQty).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(events.single().reason).contains("flat")
    }

    @Test
    fun `reconcile does NOT emit when engine and broker positions match within tolerance`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Buy","size":"0.5","avgPrice":"80000","category":"linear"}]}}"""

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        val matched =
            mapOf(
                "BYBIT_LINEAR:BTCUSDT" to Position("BYBIT_LINEAR:BTCUSDT", BigDecimal("0.5"), BigDecimal("80000")),
            )

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(matched),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events).isEmpty()
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitLinearStateRecoveryTest`
Expected: FAIL — `BybitLinearStateRecovery` doesn't exist.

- [ ] **Step 3: Implement `BybitLinearStateRecovery`**

```kotlin
// src/main/kotlin/com/qkt/broker/bybit/BybitLinearStateRecovery.kt
package com.qkt.broker.bybit

import com.qkt.broker.BrokerStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class BybitLinearStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val positionProvider: PositionProvider,
    private val getKnownOrders: () -> Map<String, BybitSpotStateRecovery.ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val positionTolerance: BigDecimal = BigDecimal("1e-8"),
) : BrokerStateRecovery {
    private val log = LoggerFactory.getLogger(BybitLinearStateRecovery::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    override fun reconcile() {
        synchronized(lock) {
            runCatching { reconcileOpenOrders() }
                .onFailure { log.warn("Open-orders reconcile failed: {}", it.message) }
            runCatching { reconcileExecutions() }
                .onFailure { log.warn("Executions reconcile failed: {}", it.message) }
            runCatching { reconcileBalances() }
                .onFailure { log.warn("Balances reconcile failed: {}", it.message) }
            runCatching { reconcilePositions() }
                .onFailure { log.warn("Positions reconcile failed: {}", it.message) }
        }
    }

    private fun reconcileOpenOrders() {
        val response = transport.postSigned("/v5/order/realtime", """{"category":"linear","openOnly":0,"limit":50}""")
        val tree = json.parseToJsonElement(response).jsonObject
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return
        val openOrderIds = list.mapNotNull { it.jsonObject["orderLinkId"]?.jsonPrimitive?.content }.toSet()
        val known = getKnownOrders()
        for ((id, _) in known) {
            if (id !in openOrderIds) {
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = id,
                        brokerOrderId = null,
                        reason = "recovered: not in open list",
                        timestamp = clock.now(),
                    ),
                )
            }
        }
    }

    private fun reconcileExecutions() {
        val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
        var cursor = ""
        var totalProcessed = 0
        val cap = 200
        while (totalProcessed < cap) {
            val body =
                if (cursor.isEmpty()) {
                    """{"category":"linear","startTime":$startTime,"limit":50}"""
                } else {
                    """{"category":"linear","startTime":$startTime,"limit":50,"cursor":"$cursor"}"""
                }
            val response = transport.postSigned("/v5/execution/list", body)
            val tree = json.parseToJsonElement(response).jsonObject
            if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
            val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return
            for (entry in list) {
                val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
                if (!seenExecIds.add(exec.execId)) continue
                val qktSymbol = "BYBIT_LINEAR:${exec.bareSymbol}"
                bus.publish(
                    BrokerEvent.OrderFilled(
                        clientOrderId = exec.clientOrderId,
                        brokerOrderId = exec.brokerOrderId,
                        symbol = qktSymbol,
                        side = exec.side,
                        price = exec.price,
                        quantity = exec.quantity,
                        timestamp = clock.now(),
                    ),
                )
                totalProcessed++
                if (totalProcessed >= cap) return
            }
            cursor = tree["result"]?.jsonObject?.get("nextPageCursor")?.jsonPrimitive?.content ?: ""
            if (cursor.isEmpty() || list.isEmpty()) break
        }
    }

    private fun reconcileBalances() {
        val response =
            transport.postSigned(
                "/v5/account/wallet-balance",
                """{"accountType":"${transport.accountType}"}""",
            )
        val parsed = BybitBalanceTranslator.parseWalletBalance(response)
        transport.updateBalances(parsed)
        bus.publish(
            BrokerEvent.BalancesUpdated(
                balances = parsed,
                source = "BYBIT_LINEAR",
                timestamp = clock.now(),
            ),
        )
    }

    private fun reconcilePositions() {
        val response = transport.postSigned("/v5/position/list", """{"category":"linear","settleCoin":"USDT"}""")
        val tree = json.parseToJsonElement(response).jsonObject
        if (tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() != 0) return
        val list = tree["result"]?.jsonObject?.get("list")?.jsonArray ?: return

        val brokerPositions: Map<String, Pair<BigDecimal, BigDecimal>> =
            list
                .mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val bareSym = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val side = obj["side"]?.jsonPrimitive?.content ?: ""
                    val rawSize = obj["size"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (rawSize.isBlank() || side.isBlank()) return@mapNotNull null
                    val size = BigDecimal(rawSize)
                    val signed = if (side == "Sell") size.negate() else size
                    val avgPrice = BigDecimal(obj["avgPrice"]?.jsonPrimitive?.content ?: "0")
                    bareSym to (signed to avgPrice)
                }.toMap()

        for ((bareSymbol, qa) in brokerPositions) {
            val (signedQty, avgPrice) = qa
            val qktSymbol = "BYBIT_LINEAR:$bareSymbol"
            val enginePos = positionProvider.positionFor(qktSymbol)

            val qtyDiffers =
                enginePos == null ||
                    enginePos.quantity.subtract(signedQty).abs() > positionTolerance
            val avgDiffers =
                enginePos == null ||
                    enginePos.avgEntryPrice.subtract(avgPrice).abs() > positionTolerance

            if (qtyDiffers || avgDiffers) {
                bus.publish(
                    BrokerEvent.PositionReconciled(
                        symbol = qktSymbol,
                        oldQty = enginePos?.quantity,
                        newQty = signedQty,
                        oldAvgPx = enginePos?.avgEntryPrice,
                        newAvgPx = avgPrice,
                        source = "BYBIT_LINEAR",
                        reason = "periodic reconcile",
                        timestamp = clock.now(),
                    ),
                )
            }
        }

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
                    ),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitLinearStateRecoveryTest`
Expected: PASS, 4 tests green.

- [ ] **Step 5: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/broker/bybit/BybitLinearStateRecovery.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitLinearStateRecoveryTest.kt
git commit -m "feat(broker): add BybitLinearStateRecovery with position reconciliation"
```

---

## Task 8: `BybitLinearBroker`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitLinearBroker.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitLinearBrokerTest.kt`

- [ ] **Step 1: Implement `BybitLinearBroker`**

Mirror `BybitSpotBroker` line-for-line, with three changes:
1. `category="linear"` everywhere
2. `supports(symbol) = symbol.startsWith("BYBIT_LINEAR:")`
3. Use `BybitLinearStateRecovery` and accept `positionProvider`

```kotlin
// src/main/kotlin/com/qkt/broker/bybit/BybitLinearBroker.kt
package com.qkt.broker.bybit

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.net.PeriodicReconciler
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class BybitLinearBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val positionProvider: PositionProvider,
    private val recoveryWindowMs: Long = 5 * 60_000L,
    private val pollIntervalMs: Long = 30_000L,
    pollExecutor: ScheduledExecutorService? = null,
) : Broker {
    private val log = LoggerFactory.getLogger(BybitLinearBroker::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val symbolByClientOrderId: MutableMap<String, String> = ConcurrentHashMap()
    private val knownOrders: MutableMap<String, BybitSpotStateRecovery.ManagedOrderView> = ConcurrentHashMap()
    private val seenExecIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastFillTime: AtomicLong = AtomicLong(clock.now() - recoveryWindowMs)

    private val reconciler: PeriodicReconciler

    override val name: String = "BybitLinear"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )

    override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_LINEAR:")

    init {
        transport.subscribe("order") { frame -> onOrderFrame(frame) }
        transport.subscribe("execution") { frame -> onExecutionFrame(frame) }

        val recovery =
            BybitLinearStateRecovery(
                transport = transport,
                bus = bus,
                clock = clock,
                positionProvider = positionProvider,
                getKnownOrders = { knownOrders.toMap() },
                lastFillTimeProvider = lastFillTime::get,
                seenExecIds = seenExecIds,
            )
        transport.onReconnect { recovery.reconcile() }
        recovery.reconcile()

        reconciler =
            if (pollExecutor != null) {
                PeriodicReconciler(intervalMs = pollIntervalMs, action = { recovery.reconcile() }, executor = pollExecutor)
            } else {
                PeriodicReconciler(intervalMs = pollIntervalMs, action = { recovery.reconcile() })
            }
        reconciler.start()

        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            symbolByClientOrderId.remove(e.clientOrderId)
            knownOrders.remove(e.clientOrderId)
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e ->
            symbolByClientOrderId.remove(e.clientOrderId)
            knownOrders.remove(e.clientOrderId)
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            symbolByClientOrderId.remove(e.clientOrderId)
            knownOrders.remove(e.clientOrderId)
        }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        if (!supports(request.symbol)) {
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "BybitLinearBroker does not support symbol ${request.symbol}",
            )
        }
        val body = BybitOrderTranslator.toCreateBody(request, category = "linear")
        val response =
            try {
                transport.postSigned("/v5/order/create", body)
            } catch (e: Exception) {
                log.warn("Bybit submit failed: {}", e.message)
                bus.publish(
                    BrokerEvent.OrderRejected(
                        clientOrderId = request.id,
                        brokerOrderId = null,
                        reason = e.message ?: "transport failure",
                        timestamp = clock.now(),
                    ),
                )
                return SubmitAck(request.id, null, accepted = false, rejectReason = e.message ?: "transport failure")
            }
        val ack = parseSubmitResponse(request.id, response)
        if (ack.accepted) {
            symbolByClientOrderId[request.id] = request.symbol
            knownOrders[request.id] =
                BybitSpotStateRecovery.ManagedOrderView(
                    clientOrderId = request.id,
                    symbol = request.symbol,
                    side = request.side,
                )
        }
        return ack
    }

    override fun cancel(orderId: String) {
        val symbol = symbolByClientOrderId[orderId] ?: return
        val body = BybitOrderTranslator.toCancelBody(symbol = symbol, orderLinkId = orderId, category = "linear")
        try {
            transport.postSigned("/v5/order/cancel", body)
        } catch (e: Exception) {
            log.warn("Bybit cancel failed for {}: {}", orderId, e.message)
        }
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val symbol =
            symbolByClientOrderId[orderId]
                ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "unknown orderId $orderId")
        val parsed = BybitSymbol.parse(symbol)
        val sb = StringBuilder("{")
        sb.append("\"category\":\"linear\",")
        sb.append("\"symbol\":\"${parsed.bare}\",")
        sb.append("\"orderLinkId\":\"$orderId\"")
        if (changes.newQuantity != null) sb.append(",\"qty\":\"${changes.newQuantity.toPlainString()}\"")
        if (changes.newLimitPrice != null) sb.append(",\"price\":\"${changes.newLimitPrice.toPlainString()}\"")
        if (changes.newStopPrice != null) sb.append(",\"triggerPrice\":\"${changes.newStopPrice.toPlainString()}\"")
        sb.append("}")
        val response =
            try {
                transport.postSigned("/v5/order/amend", sb.toString())
            } catch (e: Exception) {
                return SubmitAck(orderId, null, accepted = false, rejectReason = e.message ?: "transport failure")
            }
        return parseSubmitResponse(orderId, response)
    }

    private fun parseSubmitResponse(
        clientOrderId: String,
        responseBody: String,
    ): SubmitAck {
        val tree = json.parseToJsonElement(responseBody).jsonObject
        val retCode = tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val retMsg = tree["retMsg"]?.jsonPrimitive?.content ?: ""
        if (retCode != 0) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = clientOrderId,
                    brokerOrderId = null,
                    reason = "$retCode: $retMsg",
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(clientOrderId, null, accepted = false, rejectReason = "$retCode: $retMsg")
        }
        val brokerOrderId = tree["result"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = clientOrderId,
                brokerOrderId = brokerOrderId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(clientOrderId, brokerOrderId, accepted = true)
    }

    private fun onOrderFrame(frame: JsonObject) {
        val list = frame["data"]?.jsonArray ?: return
        for (entry in list) {
            val parsed = BybitOrderTranslator.parseOpenOrder(entry.jsonObject)
            val qktSymbol = "BYBIT_LINEAR:${parsed.bareSymbol}"
            when (parsed.status) {
                "New" ->
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = parsed.clientOrderId,
                            brokerOrderId = parsed.brokerOrderId,
                            timestamp = clock.now(),
                        ),
                    )
                "Cancelled" ->
                    bus.publish(
                        BrokerEvent.OrderCancelled(
                            clientOrderId = parsed.clientOrderId,
                            brokerOrderId = parsed.brokerOrderId,
                            reason = "WS-reported cancel",
                            timestamp = clock.now(),
                        ),
                    )
                "Rejected" ->
                    bus.publish(
                        BrokerEvent.OrderRejected(
                            clientOrderId = parsed.clientOrderId,
                            brokerOrderId = parsed.brokerOrderId,
                            reason = "WS-reported reject",
                            timestamp = clock.now(),
                        ),
                    )
            }
            symbolByClientOrderId[parsed.clientOrderId] = qktSymbol
        }
    }

    private fun onExecutionFrame(frame: JsonObject) {
        val list = frame["data"]?.jsonArray ?: return
        for (entry in list) {
            val exec = BybitOrderTranslator.parseExecution(entry.jsonObject)
            if (!seenExecIds.add(exec.execId)) continue
            val qktSymbol = "BYBIT_LINEAR:${exec.bareSymbol}"
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = exec.clientOrderId,
                    brokerOrderId = exec.brokerOrderId,
                    symbol = qktSymbol,
                    side = exec.side,
                    price = exec.price,
                    quantity = exec.quantity,
                    timestamp = clock.now(),
                ),
            )
            lastFillTime.set(clock.now())
        }
    }

    fun close() {
        reconciler.stop()
    }
}
```

- [ ] **Step 2: Write basic submission test**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitLinearBrokerTest.kt
package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitLinearBrokerTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun emptyOk() = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun makeBroker(client: FakeBybitClient): BybitLinearBroker {
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()
        client.responses["/v5/position/list"] = emptyOk()
        val broker = BybitLinearBroker(client, newBus(), FixedClock(0L), PositionTracker())
        client.posts.clear()
        return broker
    }

    @Test
    fun `name capabilities and supports`() {
        val broker = makeBroker(FakeBybitClient())
        assertThat(broker.name).isEqualTo("BybitLinear")
        assertThat(broker.supports("BYBIT_LINEAR:BTCUSDT")).isTrue
        assertThat(broker.supports("BYBIT_SPOT:BTCUSDT")).isFalse
        assertThat(broker.supports("OANDA:EURUSD")).isFalse
    }

    @Test
    fun `submit Market posts to v5 order create with category linear`() {
        val client = FakeBybitClient()
        val broker = makeBroker(client)
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c1",
                    symbol = "BYBIT_LINEAR:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(client.posts.single().path).isEqualTo("/v5/order/create")
        assertThat(client.posts.single().body).contains("\"category\":\"linear\"")
        assertThat(client.posts.single().body).contains("\"positionIdx\":0")
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isEqualTo("abc")
    }

    @Test
    fun `submit rejects symbol that does not start with BYBIT_LINEAR`() {
        val broker = makeBroker(FakeBybitClient())

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c1",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("does not support")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitLinearBrokerTest`
Expected: PASS, 3 tests green.

- [ ] **Step 4: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/broker/bybit/BybitLinearBroker.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitLinearBrokerTest.kt
git commit -m "feat(broker): add BybitLinearBroker for USDT perpetuals"
```

---

## Task 9: Wire `PositionReconciled` subscription in application entry points

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt` (only if it sets up subscriptions inline; otherwise routes through pipeline)
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Modify: `src/main/kotlin/com/qkt/app/Main.kt` (if it wires PositionTracker directly)

- [ ] **Step 1: Add subscription in `TradingPipeline`**

Find the existing line:
```kotlin
bus.subscribe<BrokerEvent.OrderFilled> { e ->
    val realized = positions.applyFill(e)
    // ...
}
```

Add after it:
```kotlin
bus.subscribe<BrokerEvent.PositionReconciled> { e ->
    positions.reset(e.symbol, e.newQty, e.newAvgPx)
}
```

- [ ] **Step 2: Same for `Backtest.kt`, `LiveSession.kt`, `Main.kt` where applicable**

For each file, check whether it wires `bus.subscribe<BrokerEvent.OrderFilled>` directly. If yes, add the parallel `PositionReconciled` subscription. If it only delegates to `TradingPipeline`, no change needed.

```bash
grep -l 'bus.subscribe<BrokerEvent.OrderFilled>' src/main/kotlin/com/qkt/app
# inspect each, add subscription if needed
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/app/
git commit -m "feat(app): wire PositionReconciled subscription to PositionTracker reset"
```

---

## Task 10: Full check + demo invariant

- [ ] **Step 1: Full clean build + all tests**

Run: `./gradlew clean check`
Expected: BUILD SUCCESSFUL. ~570+ tests pass.

- [ ] **Step 2: Demo invariant**

Run: `./gradlew run 2>&1 | grep -cE 'FILLED|REJECTED'`
Expected: `10`.

- [ ] **Step 3: Final ktlintFormat + commit any reformats**

```bash
./gradlew ktlintFormat
git status --short
# if anything modified:
git add -A
git commit -m "style: ktlintFormat after 7h additions"
```

---

## Task 11: Phase 7h changelog

**Files:**
- Create: `docs/phases/phase-7h-derivatives-and-rate-limit.md`

- [ ] **Step 1: Write the changelog**

Use `docs/phases/phase-7g-reconciliation-and-balances.md` as a template. Required sections per qkt skill §6:

1. **Summary** — 2-4 sentences.
2. **What's new** — bullets covering: BybitLinearBroker, BybitLinearStateRecovery, BrokerStateRecovery interface, BybitSpotStateRecovery rename, PositionReconciled event, PositionTracker.reset, 429 handling, accountType env var, pagination, BybitOrderTranslator category param.
3. **Migration from previous phase** — table covering renames + new required wiring.
4. **Usage cookbook** — at least 5 worked examples:
   - Construct `BybitLinearBroker` and add to composite
   - Subscribe to `PositionReconciled` for monitoring drift
   - Override `BYBIT_ACCOUNT_TYPE` for legacy accounts
   - Catch `BybitRateLimitException` in a strategy
   - Trade BTCUSDT perpetual with Limit + Stop
5. **Testing patterns** — `FakeBybitClient.responsesByPredicate`, position-list response shape, 429 simulation pattern.
6. **Known limitations** — inverse perpetuals deferred, hedge mode not supported, no pre-emptive rate limit, no leverage config, no funding rate.
7. **References** — spec, plan, Bybit V5 docs.

200-500 lines.

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-7h-derivatives-and-rate-limit.md
git commit -m "docs: add phase 7h changelog"
```

---

## Task 12: Final verification + finishing-a-development-branch

- [ ] **Step 1: Verify branch state**

Run:
```bash
git log --oneline main..HEAD
git status --short
./gradlew check
```

Expected:
- ~13 commits ahead of main (spec, plan, T1, T2, T3, T4, T5, T6, T7, T8, T9, ktlint, changelog).
- Clean working tree (or only `?? tt.txt`).
- BUILD SUCCESSFUL.

- [ ] **Step 2: Use finishing-a-development-branch skill**

Announce: "I'm using the finishing-a-development-branch skill to complete this work."

Default for this project: option 1 (merge `--no-ff` to main, delete branch).

Merge commit message: `merge: phase 7h derivatives and rate limiting`.

Post-merge:
```bash
git checkout main
git log --oneline -5
./gradlew check
```

Expected: BUILD SUCCESSFUL.

---

## Self-Review Checklist

Before marking the plan complete:

- [ ] Every type, method, and field referenced in a later task is defined in an earlier task.
- [ ] No "TBD", "TODO", "fill in", or "similar to above" text in any step.
- [ ] Every code step shows the actual code, not a description.
- [ ] Every test step has both the test code AND the verification command.
- [ ] Every commit step has the exact `git commit -m` line.
- [ ] All files referenced exist after their creating task; modifications happen only on existing files.
- [ ] Spec coverage:
  - Spec §5 BrokerStateRecovery interface → T1.
  - Spec §6 Translator category param → T2.
  - Spec §7 Rate limiting → T4.
  - Spec §8 Pagination → T3.
  - Spec §9 BybitLinearStateRecovery → T7.
  - Spec §10 PositionReconciled event → T6.
  - Spec §11 PositionTracker.reset → T5.
  - Spec §12 BybitLinearBroker → T8.
  - Spec §13 accountType configuration → T4.
  - Spec §17 testing → T1, T2, T3, T4, T5, T6, T7, T8, T9.
  - Spec §18 migration → T9, T11 changelog.
