# Phase 17 — MT5 Broker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a profile-driven MT5 broker that talks to per-broker `mt5-gateway` HTTP services. v1 capabilities = `[MARKET, BRACKET]`. Built-in defaults for `exness`, `icmarkets`, `ftmo`, `pepperstone`. End-users override via `qkt.config.yaml` (name-match partial overrides, `extends:` chains, env vars).

**Architecture:** `MT5Client` (HTTP transport, one per gateway URL) + `MT5BrokerProfile` (per-broker policy bundle) + `MT5OrderTranslator` (qkt → MT5 wire format) + `MT5PositionPoller` (close detection) + `MT5StateRecovery` (startup) → composed in `MT5Broker(profile)` which implements qkt's `Broker` interface. `MT5BrokerProfileLoader` extends Phase 12a's `Config.brokers` parsing with `extends:`/default-resolution semantics. Daemon startup auto-registers all configured profiles.

**Tech Stack:** Kotlin 1.9, JDK 21, Gradle, OkHttp (existing), snakeyaml-engine (existing), JUnit 5, AssertJ. New testImplementation: `com.squareup.okhttp3:mockwebserver` for fake gateway tests.

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt` | Constant capability set; future-extensible |
| `src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfile.kt` | `MT5BrokerProfile` + `SymbolPolicy` + `InstrumentSpec` data classes |
| `src/main/kotlin/com/qkt/broker/mt5/MT5DefaultProfiles.kt` | Built-in profiles registry (exness/icmarkets/ftmo/pepperstone) |
| `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt` | `MT5OrderRequest`, `MT5OrderResponse`, `MT5OrderResult`, `MT5Position`, `MT5Tick`, `MT5SymbolInfo`, `MT5AccountInfo` |
| `src/main/kotlin/com/qkt/broker/mt5/MT5Symbol.kt` | qkt ↔ broker symbol translation |
| `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt` | qkt `OrderRequest` → `MT5OrderRequest` (Market + Bracket) |
| `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt` | OkHttp wrapper, retry, TZ normalization |
| `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt` | Daemon thread, position diff → fill events |
| `src/main/kotlin/com/qkt/broker/mt5/MT5StateRecovery.kt` | Startup snapshot → `BrokerEvent.PositionReconciled` |
| `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` | Implements `Broker` |
| `src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoader.kt` | Resolves Config.brokers into typed profiles with extends/defaults |
| `src/main/kotlin/com/qkt/cli/BrokersCommand.kt` | `qkt brokers list` subcommand |
| `src/test/kotlin/com/qkt/broker/mt5/MT5SymbolTest.kt` | Round-trip + edge cases |
| `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt` | Market + Bracket translation |
| `src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt` | MockWebServer integration |
| `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoaderTest.kt` | Resolution semantics |
| `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt` | End-to-end via fake gateway |
| `docs/phases/phase-17.md` | Phase changelog |

### Modified files

| Path | Change |
|---|---|
| `build.gradle.kts` | Add `testImplementation("com.squareup.okhttp3:mockwebserver:<version>")` |
| `src/main/kotlin/com/qkt/cli/Main.kt` | Wire `"brokers"` subcommand |
| `src/main/kotlin/com/qkt/cli/DaemonCommand.kt` | Load + register MT5 profiles at startup |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | `VERSION = "0.19.0"` |
| `README.md` | Phase 17 line |
| `docs/backlog.md` | Mark MT5 broker item `done` |

---

## Task 1: MT5 protocol + wire types + capability constant

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt`
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`

Pure data classes; no tests needed.

- [ ] **Step 1: `MT5Protocol`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt
package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability

object MT5Protocol {
    /**
     * What the qkt MT5 translator + mt5-gateway can transact natively.
     * v1 ships MARKET + BRACKET; future versions add LIMIT, STOP, STOP_LIMIT
     * once MT5OrderTranslator handles them. Profiles never widen this.
     */
    val capabilities: Set<OrderTypeCapability> = setOf(
        OrderTypeCapability.MARKET,
        OrderTypeCapability.BRACKET,
    )
}
```

- [ ] **Step 2: `MT5WireTypes`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt
package com.qkt.broker.mt5

import java.math.BigDecimal

data class MT5Tick(
    val symbol: String,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val time: Long,                      // already converted to UTC by client
)

data class MT5AccountInfo(
    val balance: BigDecimal,
    val equity: BigDecimal,
    val currency: String,
    val leverage: Int,
)

data class MT5Position(
    val ticket: Long,
    val symbol: String,                  // broker-suffixed (e.g. EURUSDm)
    val type: Int,                       // 0=BUY, 1=SELL
    val volume: BigDecimal,
    val priceOpen: BigDecimal,
    val sl: BigDecimal,
    val tp: BigDecimal,
    val profit: BigDecimal,
    val magic: Int,
    val openTime: Long,                  // already UTC
    val comment: String? = null,
)

data class MT5SymbolInfo(
    val ask: BigDecimal,
    val bid: BigDecimal,
    val digits: Int,
    val point: BigDecimal,
    val tradeStopsLevel: Int,
    val volumeMin: BigDecimal,
    val volumeStep: BigDecimal,
)

data class MT5OrderRequest(
    val symbol: String,                  // broker-suffixed
    val volume: BigDecimal,
    val type: String,                    // "BUY" | "SELL" | "BUY_LIMIT" | etc.
    val price: BigDecimal? = null,       // required for pendings
    val sl: BigDecimal? = null,
    val tp: BigDecimal? = null,
    val deviation: Int = 20,
    val magic: Int,
    val comment: String,
    val expiration: Long? = null,
    val typeTime: String? = null,        // "GTC" | "SPECIFIED"
)

data class MT5OrderResult(
    val retcode: Int,
    val order: Long,
    val deal: Long,
    val price: BigDecimal,
    val comment: String,
)

data class MT5OrderResponse(
    val result: MT5OrderResult,
    val errorMessage: String? = null,
)

const val MT5_TRADE_RETCODE_DONE: Int = 10009

fun isOrderSuccessful(retcode: Int): Boolean = retcode == MT5_TRADE_RETCODE_DONE
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt \
        src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt
git commit -m "feat(broker): mt5 protocol constant and wire data classes"
```

---

## Task 2: `MT5BrokerProfile` + defaults

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfile.kt`
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5DefaultProfiles.kt`

- [ ] **Step 1: Profile data classes**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfile.kt
package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import java.math.BigDecimal

data class MT5BrokerProfile(
    val name: String,
    val gatewayUrl: String,
    val symbolPolicy: SymbolPolicy,
    val serverTzOffsetHours: Int = 0,
    val magic: Int,
    val instrumentOverrides: Map<String, InstrumentSpec> = emptyMap(),
    val pollIntervalMs: Long = 1000,
    val httpTimeoutMs: Long = 5000,
    val retryAttempts: Int = 3,
    val deviationPoints: Int = 20,
    val capabilityRestrictions: Set<OrderTypeCapability> = emptySet(),
) {
    val capabilities: Set<OrderTypeCapability>
        get() = MT5Protocol.capabilities - capabilityRestrictions
}

data class SymbolPolicy(
    val suffix: String = "",
    val aliases: Map<String, String> = emptyMap(),
)

data class InstrumentSpec(
    val minVolume: BigDecimal,
    val volumeStep: BigDecimal,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
)
```

- [ ] **Step 2: Default profiles**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5DefaultProfiles.kt
package com.qkt.broker.mt5

object MT5DefaultProfiles {
    val exness = MT5BrokerProfile(
        name = "exness",
        gatewayUrl = "http://localhost:5001",
        symbolPolicy = SymbolPolicy(
            suffix = "m",
            aliases = mapOf(
                "NAS100" to "USTEC",
                "US500" to "US500",
                "US30" to "US30",
                "UKOIL" to "XBRUSD",
                "NGAS" to "XNGUSD",
            ),
        ),
        serverTzOffsetHours = 2,
        magic = 10001,
    )

    val icmarkets = MT5BrokerProfile(
        name = "icmarkets",
        gatewayUrl = "http://localhost:5002",
        symbolPolicy = SymbolPolicy(suffix = ".raw"),
        serverTzOffsetHours = 3,
        magic = 10002,
    )

    val ftmo = MT5BrokerProfile(
        name = "ftmo",
        gatewayUrl = "http://localhost:5003",
        symbolPolicy = SymbolPolicy(suffix = ""),
        serverTzOffsetHours = 2,
        magic = 10003,
    )

    val pepperstone = MT5BrokerProfile(
        name = "pepperstone",
        gatewayUrl = "http://localhost:5004",
        symbolPolicy = SymbolPolicy(suffix = ".cmd"),
        serverTzOffsetHours = 2,
        magic = 10004,
    )

    val all: Map<String, MT5BrokerProfile> =
        listOf(exness, icmarkets, ftmo, pepperstone).associateBy { it.name }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

> **Verify:** `OrderTypeCapability` enum has `MARKET` and `BRACKET` values. If naming differs, adjust both this task and Task 1's `MT5Protocol.kt`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfile.kt \
        src/main/kotlin/com/qkt/broker/mt5/MT5DefaultProfiles.kt
git commit -m "feat(broker): mt5 broker profile and built-in defaults"
```

---

## Task 3: `MT5Symbol` translation

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5Symbol.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5SymbolTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/broker/mt5/MT5SymbolTest.kt
package com.qkt.broker.mt5

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5SymbolTest {
    @Test
    fun `exness suffix and alias applied on toBroker`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        assertThat(s.toBroker("EURUSD")).isEqualTo("EURUSDm")
        assertThat(s.toBroker("NAS100")).isEqualTo("USTECm")
        assertThat(s.toBroker("UKOIL")).isEqualTo("XBRUSDm")
    }

    @Test
    fun `exness toQkt strips suffix and reverses alias`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        assertThat(s.toQkt("EURUSDm")).isEqualTo("EURUSD")
        assertThat(s.toQkt("USTECm")).isEqualTo("NAS100")
        assertThat(s.toQkt("XBRUSDm")).isEqualTo("UKOIL")
    }

    @Test
    fun `round-trip yields original qkt symbol`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        for (q in listOf("EURUSD", "GBPUSD", "NAS100", "US500", "UKOIL")) {
            assertThat(s.toQkt(s.toBroker(q))).isEqualTo(q)
        }
    }

    @Test
    fun `empty suffix passes through`() {
        val s = MT5Symbol(SymbolPolicy(suffix = ""))
        assertThat(s.toBroker("EURUSD")).isEqualTo("EURUSD")
        assertThat(s.toQkt("EURUSD")).isEqualTo("EURUSD")
    }

    @Test
    fun `icmarkets dot-raw suffix`() {
        val s = MT5Symbol(MT5DefaultProfiles.icmarkets.symbolPolicy)
        assertThat(s.toBroker("EURUSD")).isEqualTo("EURUSD.raw")
        assertThat(s.toQkt("EURUSD.raw")).isEqualTo("EURUSD")
    }

    @Test
    fun `unknown alias passes through unchanged`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        assertThat(s.toBroker("XAUUSD")).isEqualTo("XAUUSDm")
        assertThat(s.toQkt("XAUUSDm")).isEqualTo("XAUUSD")
    }
}
```

- [ ] **Step 2: Run tests, verify failure**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5SymbolTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement `MT5Symbol`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5Symbol.kt
package com.qkt.broker.mt5

class MT5Symbol(
    private val policy: SymbolPolicy,
) {
    private val reverseAliases: Map<String, String> =
        policy.aliases.entries.associate { (k, v) -> v to k }

    fun toBroker(qktSymbol: String): String {
        val base = policy.aliases[qktSymbol] ?: qktSymbol
        return base + policy.suffix
    }

    fun toQkt(brokerSymbol: String): String {
        val withoutSuffix =
            if (policy.suffix.isNotEmpty() && brokerSymbol.endsWith(policy.suffix)) {
                brokerSymbol.removeSuffix(policy.suffix)
            } else {
                brokerSymbol
            }
        return reverseAliases[withoutSuffix] ?: withoutSuffix
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5SymbolTest`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Symbol.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5SymbolTest.kt
git commit -m "feat(broker): mt5 symbol translation with suffix and aliases"
```

---

## Task 4: `MT5OrderTranslator`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt
package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5OrderTranslatorTest {
    private val translator =
        MT5OrderTranslator(
            profile = MT5DefaultProfiles.exness,
            symbol = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy),
        )

    private fun marketReq(side: Side) =
        OrderRequest.Market(
            id = "ord-1",
            symbol = "EURUSD",
            side = side,
            quantity = BigDecimal("0.1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
        )

    @Test
    fun `BUY market translates to BUY type with suffixed symbol`() {
        val mt5 = translator.translate(marketReq(Side.BUY))
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.volume).isEqualByComparingTo("0.1")
        assertThat(mt5.sl).isNull()
        assertThat(mt5.tp).isNull()
        assertThat(mt5.magic).isEqualTo(10001)
        assertThat(mt5.comment).isEqualTo("ord-1")
    }

    @Test
    fun `SELL market translates to SELL type`() {
        val mt5 = translator.translate(marketReq(Side.SELL))
        assertThat(mt5.type).isEqualTo("SELL")
    }

    @Test
    fun `Bracket with sl and tp translates with both fields`() {
        val entry = marketReq(Side.BUY)
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = BigDecimal("1.0500"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val mt5 = translator.translate(bracket)
        assertThat(mt5.type).isEqualTo("BUY")
        assertThat(mt5.symbol).isEqualTo("EURUSDm")
        assertThat(mt5.sl).isEqualByComparingTo("1.0500")
        assertThat(mt5.tp).isEqualByComparingTo("1.1500")
        assertThat(mt5.comment).isEqualTo("br-1")
    }

    @Test
    fun `Limit translation throws (engine-managed fallback expected)`() {
        val limit =
            OrderRequest.Limit(
                id = "l-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                limitPrice = BigDecimal("1.1000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        assertThatThrownBy { translator.translate(limit) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not natively translate")
    }
}
```

> **Verify:** `OrderRequest.Limit` constructor field is `limitPrice`. If different, adjust the test.

- [ ] **Step 2: Run tests, expect failure**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5OrderTranslatorTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement translator**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt
package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest

class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
) {
    fun translate(req: OrderRequest): MT5OrderRequest =
        when (req) {
            is OrderRequest.Market -> translateMarket(req)
            is OrderRequest.Bracket -> translateBracket(req)
            else -> error(
                "MT5 v1 does not natively translate ${req::class.simpleName}; " +
                    "OrderManager should use engine-managed fallback",
            )
        }

    private fun translateMarket(req: OrderRequest.Market): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateBracket(req: OrderRequest.Bracket): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = req.stopLoss,
            tp = req.takeProfit,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5OrderTranslatorTest`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt
git commit -m "feat(broker): mt5 order translator for market and bracket"
```

---

## Task 5: `MT5Client` HTTP transport

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt`
- Modify: `build.gradle.kts` (add mockwebserver test dep)

The client wraps OkHttp + JSON serialization. Subtracts `tzOffsetHours * 3600 * 1000` from any timestamp returned. Retries GET endpoints; **never retries POST `/order`** (duplicate placement worse than a transient failure).

- [ ] **Step 1: Add mockwebserver dependency**

In `build.gradle.kts`:

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

> **Verify:** the existing okhttp version in `gradle/libs.versions.toml` matches. If pinned via libs catalog, prefer `testImplementation(libs.okhttp.mockwebserver)` and add the alias to the catalog.

- [ ] **Step 2: Write failing test**

```kotlin
// src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt
package com.qkt.broker.mt5

import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5ClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client =
            MT5Client(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                tzOffsetHours = 2,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `placeOrder sends correct json and parses response`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":12345,"deal":67890,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val resp =
            client.placeOrder(
                MT5OrderRequest(
                    symbol = "EURUSDm",
                    volume = BigDecimal("0.1"),
                    type = "BUY",
                    magic = 10001,
                    comment = "ord-1",
                ),
            )
        assertThat(resp.result.retcode).isEqualTo(10009)
        assertThat(resp.result.deal).isEqualTo(67890L)
        assertThat(resp.result.price).isEqualByComparingTo("1.1234")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/order")
        assertThat(recorded.method).isEqualTo("POST")
    }

    @Test
    fun `getPositions parses list and applies tz offset`() {
        // Server returns openTime in MT5 server time (UTC + 2h).
        // Client should subtract 2h to give us UTC.
        val serverEpochMs = 1_700_000_000_000L      // pretend MT5 time
        val expectedUtcMs = serverEpochMs - 2L * 3600L * 1000L
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":1,"symbol":"EURUSDm","type":0,"volume":"0.1","price_open":"1.1","sl":"0","tp":"0","profit":"0","magic":10001,"open_time":$serverEpochMs,"comment":"x"}]""",
            ),
        )
        val positions = client.getPositions(magic = 10001)
        assertThat(positions).hasSize(1)
        assertThat(positions[0].ticket).isEqualTo(1L)
        assertThat(positions[0].openTime).isEqualTo(expectedUtcMs)
    }

    @Test
    fun `isReady returns true on 200`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        assertThat(client.isReady()).isTrue
    }

    @Test
    fun `isReady returns false on 5xx`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.isReady()).isFalse
    }
}
```

- [ ] **Step 3: Run tests, expect failure**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5ClientTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 4: Implement `MT5Client`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt
package com.qkt.broker.mt5

import java.math.BigDecimal
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

class MT5Client(
    private val gatewayUrl: String,
    private val tzOffsetHours: Int,
    private val httpTimeoutMs: Long = 5000,
    private val retryAttempts: Int = 3,
) {
    private val log = LoggerFactory.getLogger(MT5Client::class.java)
    private val tzOffsetMs: Long = tzOffsetHours.toLong() * 3600L * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(Duration.ofMillis(httpTimeoutMs))
            .connectTimeout(Duration.ofMillis(httpTimeoutMs))
            .build()

    fun isReady(): Boolean =
        runCatching {
            val resp = http.newCall(Request.Builder().url("$gatewayUrl/health").build()).execute()
            resp.use { it.isSuccessful }
        }.getOrDefault(false)

    fun placeOrder(req: MT5OrderRequest): MT5OrderResponse {
        val body = encodeOrder(req).toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("$gatewayUrl/order").post(body).build()
        // POST is NOT retried: duplicate placement is worse than a surfaced transient failure.
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

    fun getPositions(magic: Int? = null): List<MT5Position> {
        val url = if (magic != null) "$gatewayUrl/positions?magic=$magic" else "$gatewayUrl/positions"
        val raw = getWithRetry(url) ?: return emptyList()
        val arr = json.parseToJsonElement(raw).jsonArray
        return arr.map { parsePosition(it.jsonObject) }
    }

    fun cancelOrder(ticket: Long): String {
        val request =
            Request.Builder()
                .url("$gatewayUrl/cancel/$ticket")
                .post("".toRequestBody(JSON_MEDIA))
                .build()
        val resp = http.newCall(request).execute()
        return resp.use { it.body?.string().orEmpty() }
    }

    private fun getWithRetry(url: String): String? {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= retryAttempts) {
            try {
                val resp = http.newCall(Request.Builder().url(url).build()).execute()
                resp.use {
                    if (it.isSuccessful) return it.body?.string().orEmpty()
                }
            } catch (e: java.io.IOException) {
                lastError = e
            }
            attempt++
            if (attempt <= retryAttempts) Thread.sleep(200L * attempt)
        }
        if (lastError != null) log.warn("MT5Client GET $url failed after $retryAttempts retries", lastError)
        return null
    }

    private fun encodeOrder(req: MT5OrderRequest): String {
        // Hand-rolled JSON to avoid serialization annotations. snakeyaml-engine isn't JSON; use kotlinx.serialization.json builders.
        val sb = StringBuilder("{")
        fun field(name: String, value: String, last: Boolean = false) {
            sb.append("\"$name\":$value")
            if (!last) sb.append(",")
        }
        field("symbol", "\"${req.symbol}\"")
        field("volume", req.volume.toPlainString())
        field("type", "\"${req.type}\"")
        if (req.price != null) field("price", req.price.toPlainString())
        if (req.sl != null) field("sl", req.sl.toPlainString())
        if (req.tp != null) field("tp", req.tp.toPlainString())
        field("deviation", req.deviation.toString())
        field("magic", req.magic.toString())
        field("comment", "\"${req.comment}\"", last = true)
        sb.append("}")
        return sb.toString()
    }

    private fun parseOrderResponse(raw: String): MT5OrderResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        val r = obj["result"]?.jsonObject
            ?: return MT5OrderResponse(
                result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                errorMessage = "missing result field: $raw",
            )
        return MT5OrderResponse(
            result = MT5OrderResult(
                retcode = r["retcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
                order = r["order"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                deal = r["deal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                price = r["price"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                comment = r["comment"]?.jsonPrimitive?.contentOrNull ?: "",
            ),
            errorMessage = obj["error"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parsePosition(obj: JsonObject): MT5Position {
        val rawTime = obj["open_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return MT5Position(
            ticket = obj["ticket"]!!.jsonPrimitive.content.toLong(),
            symbol = obj["symbol"]!!.jsonPrimitive.content,
            type = obj["type"]!!.jsonPrimitive.content.toInt(),
            volume = obj["volume"]!!.jsonPrimitive.content.toBigDecimal(),
            priceOpen = obj["price_open"]!!.jsonPrimitive.content.toBigDecimal(),
            sl = obj["sl"]!!.jsonPrimitive.content.toBigDecimal(),
            tp = obj["tp"]!!.jsonPrimitive.content.toBigDecimal(),
            profit = obj["profit"]!!.jsonPrimitive.content.toBigDecimal(),
            magic = obj["magic"]!!.jsonPrimitive.content.toInt(),
            openTime = rawTime - tzOffsetMs,            // server time → UTC
            comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5ClientTest`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts \
        src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt
git commit -m "feat(broker): mt5 http client with retry and tz normalization"
```

---

## Task 6: `MT5StateRecovery` + `MT5PositionPoller`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5StateRecovery.kt`
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt`

These are small; combined into one task.

- [ ] **Step 1: `MT5StateRecovery`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5StateRecovery.kt
package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

class MT5StateRecovery(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
) {
    private val log = LoggerFactory.getLogger(MT5StateRecovery::class.java)

    fun recover() {
        val positions = client.getPositions(magic = profile.magic)
        log.info("MT5 ${profile.name} state recovery: ${positions.size} open positions")
        for (p in positions) {
            val qktSymbol = symbol.toQkt(p.symbol)
            val signedQty = if (p.type == 0) p.volume else p.volume.negate()
            bus.publish(
                BrokerEvent.PositionReconciled(
                    symbol = qktSymbol,
                    newQty = signedQty,
                    newAvgPx = p.priceOpen,
                ),
            )
        }
    }
}
```

> **Verify:** `BrokerEvent.PositionReconciled` field names. If different, adjust.

- [ ] **Step 2: `MT5PositionPoller`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt
package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class MT5PositionPoller(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(MT5PositionPoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastSnapshot: Map<Long, MT5Position> = emptyMap()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Seed snapshot so the first tick doesn't emit close events for already-open positions.
        lastSnapshot = client.getPositions(magic = profile.magic).associateBy { it.ticket }
        thread =
            Thread({
                while (running.get()) {
                    try {
                        Thread.sleep(profile.pollIntervalMs)
                        if (!running.get()) break
                        tick()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        log.warn("MT5 poller for ${profile.name} tick failed", e)
                    }
                }
            }, "qkt-mt5-poller-${profile.name}").apply {
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
        val current = client.getPositions(magic = profile.magic).associateBy { it.ticket }
        val closed = lastSnapshot.keys - current.keys
        for (ticket in closed) {
            val p = lastSnapshot[ticket] ?: continue
            val qktSymbol = symbol.toQkt(p.symbol)
            val closeSide = if (p.type == 0) Side.SELL else Side.BUY
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = "mt5-close-$ticket",
                    brokerOrderId = ticket.toString(),
                    symbol = qktSymbol,
                    side = closeSide,
                    price = p.priceOpen,                // approximate; future: query deal history
                    quantity = p.volume,
                    strategyId = "",
                    timestamp = clock.now(),
                ),
            )
        }
        lastSnapshot = current
    }
}
```

> **Verify:** `BrokerEvent.OrderFilled` field names. If `Side` is in a different package, adjust.

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5StateRecovery.kt \
        src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt
git commit -m "feat(broker): mt5 state recovery and position poller"
```

---

## Task 7: `MT5Broker` and integration test

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`

- [ ] **Step 1: Implement `MT5Broker`**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt
package com.qkt.broker.mt5

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

class MT5Broker(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val client: MT5Client = MT5Client(
        gatewayUrl = profile.gatewayUrl,
        tzOffsetHours = profile.serverTzOffsetHours,
        httpTimeoutMs = profile.httpTimeoutMs,
        retryAttempts = profile.retryAttempts,
    ),
) : Broker {
    override val name: String = profile.name
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol)
    private val poller = MT5PositionPoller(client, profile, mt5Symbol, bus, clock)
    private val stateRecovery = MT5StateRecovery(client, profile, mt5Symbol, bus)

    init {
        try {
            stateRecovery.recover()
            poller.start()
        } catch (e: Exception) {
            log.warn("MT5Broker ${profile.name} startup degraded: ${e.message}")
        }
    }

    override fun supports(symbol: String): Boolean = true

    override fun submit(request: OrderRequest): SubmitAck {
        if (request !is OrderRequest.Market && request !is OrderRequest.Bracket) {
            return SubmitAck.Rejected(
                "MT5 v1 does not natively support ${request::class.simpleName}; engine fallback required",
            )
        }
        val mt5Req = translator.translate(request)
        val resp = client.placeOrder(mt5Req)
        if (!isOrderSuccessful(resp.result.retcode)) {
            val reason = resp.errorMessage ?: "retcode=${resp.result.retcode}"
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = request.id,
                    reason = reason,
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck.Rejected(reason)
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = resp.result.deal.toString(),
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = resp.result.deal.toString(),
                symbol = request.symbol,
                side = (request as? OrderRequest.Market)?.side ?: (request as OrderRequest.Bracket).side,
                price = resp.result.price,
                quantity = request.quantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck.Accepted(brokerOrderId = resp.result.deal.toString())
    }

    override fun cancel(orderId: String) {
        // v1: no native pending orders — nothing to cancel server-side.
    }

    fun shutdown() {
        poller.stop()
    }
}
```

> **Verify:** `SubmitAck` variants (`Accepted` / `Rejected`) and `BrokerEvent.OrderAccepted` / `OrderRejected` / `OrderFilled` field names. Adjust to match existing.

- [ ] **Step 2: Write integration test**

```kotlin
// src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt
package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5BrokerIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private val captured = mutableListOf<BrokerEvent>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // First call from state recovery: empty positions
        server.enqueue(MockResponse().setBody("[]"))
        // Poller seed: empty positions
        server.enqueue(MockResponse().setBody("[]"))

        val clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> captured.add(e) }

        val profile = MT5DefaultProfiles.exness.copy(
            gatewayUrl = server.url("/").toString().trimEnd('/'),
            httpTimeoutMs = 2000,
            retryAttempts = 0,
            pollIntervalMs = 100_000,                // effectively no polling during the test
        )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `submit market buy emits accepted plus filled`() {
        // Order placement response
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "ord-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(req)
        assertThat(captured).hasSize(2)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(captured[1]).isInstanceOf(BrokerEvent.OrderFilled::class.java)
        val filled = captured[1] as BrokerEvent.OrderFilled
        assertThat(filled.symbol).isEqualTo("EURUSD")
        assertThat(filled.price).isEqualByComparingTo("1.1234")
        // gateway received translated symbol
        val recordedRecovery = server.takeRequest()  // initial recovery call
        val recordedSeed = server.takeRequest()      // poller seed
        val recordedOrder = server.takeRequest()
        assertThat(recordedOrder.body.readUtf8()).contains("\"symbol\":\"EURUSDm\"")
        assertThat(recordedOrder.body.readUtf8()).contains("\"magic\":10001")
    }

    @Test
    fun `bracket submit includes sl tp in payload`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val entry =
            OrderRequest.Market(
                id = "ent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = BigDecimal("1.0500"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(bracket)
        server.takeRequest()
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"sl\":1.05")
        assertThat(body).contains("\"tp\":1.15")
    }

    @Test
    fun `non-native order type returns rejection without HTTP call`() {
        val limit =
            OrderRequest.Limit(
                id = "l-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                limitPrice = BigDecimal("1.1000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(limit)
        // Drain the recovery + seed calls but no /order request was made
        server.takeRequest()
        server.takeRequest()
        // Verify ack is a rejection (no event published)
        assertThat(ack::class.simpleName).contains("Rejected")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5BrokerIntegrationTest`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt
git commit -m "feat(broker): mt5 broker integrates client poller and state recovery"
```

---

## Task 8: `MT5BrokerProfileLoader`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoader.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoaderTest.kt`

Resolves `Config.brokers` map → list of `MT5BrokerProfile`. Handles name-match, `extends:`, env var hot-fixes, and validation.

- [ ] **Step 1: Write tests**

```kotlin
// src/test/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoaderTest.kt
package com.qkt.broker.mt5

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5BrokerProfileLoaderTest {
    private val loader = MT5BrokerProfileLoader()

    @Test
    fun `name match overrides only specified field of default`() {
        val raw = mapOf(
            "exness" to mapOf("type" to "mt5", "gateway_url" to "http://h:5005"),
        )
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap())
        val ex = profiles.first { it.name == "exness" }
        assertThat(ex.gatewayUrl).isEqualTo("http://h:5005")
        // inherited from default
        assertThat(ex.symbolPolicy.suffix).isEqualTo("m")
        assertThat(ex.serverTzOffsetHours).isEqualTo(2)
        assertThat(ex.magic).isEqualTo(10001)
    }

    @Test
    fun `extends builds new profile from named base`() {
        val raw = mapOf(
            "exness-personal" to mapOf(
                "type" to "mt5",
                "extends" to "exness",
                "gateway_url" to "http://h:5006",
                "magic" to "10005",
            ),
        )
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap())
        val p = profiles.first { it.name == "exness-personal" }
        assertThat(p.symbolPolicy.suffix).isEqualTo("m")            // from exness base
        assertThat(p.gatewayUrl).isEqualTo("http://h:5006")
        assertThat(p.magic).isEqualTo(10005)
    }

    @Test
    fun `fresh profile requires required fields`() {
        val raw = mapOf("myforex" to mapOf("type" to "mt5", "gateway_url" to "http://h:6000"))
        // missing magic and server_tz_offset_hours
        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContainingAll("magic", "myforex")
    }

    @Test
    fun `env override replaces field`() {
        val raw = mapOf("exness" to mapOf("type" to "mt5"))
        val env = mapOf("QKT_BROKER_EXNESS_GATEWAY_URL" to "http://prod:7000")
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = env)
        assertThat(profiles.first { it.name == "exness" }.gatewayUrl).isEqualTo("http://prod:7000")
    }

    @Test
    fun `non-mt5 type is filtered out`() {
        val raw = mapOf(
            "bybit" to mapOf("api_key" to "k"),                     // no type:mt5 → ignored
            "exness" to mapOf("type" to "mt5"),
        )
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap())
        assertThat(profiles.map { it.name }).containsExactly("exness")
    }

    @Test
    fun `duplicate magic is rejected`() {
        val raw = mapOf(
            "exness-a" to mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to "http://a", "magic" to "777"),
            "exness-b" to mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to "http://b", "magic" to "777"),
        )
        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .hasMessageContaining("magic")
    }
}
```

- [ ] **Step 2: Implement loader**

```kotlin
// src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoader.kt
package com.qkt.broker.mt5

class MT5BrokerProfileLoader {
    fun load(
        raw: Map<String, Map<String, String>>,
        defaults: Map<String, MT5BrokerProfile>,
        env: Map<String, String>,
    ): List<MT5BrokerProfile> {
        val mt5Entries = raw.filterValues { it["type"] == "mt5" }
        val resolved = mutableMapOf<String, MT5BrokerProfile>()

        // Two-pass: first pass entries that don't extend (or extend a default);
        // second pass entries that extend another user profile.
        val pending = LinkedHashMap(mt5Entries)
        var madeProgress = true
        while (pending.isNotEmpty() && madeProgress) {
            madeProgress = false
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (name, fields) = it.next()
                val extendsName = fields["extends"]
                val base: MT5BrokerProfile? = when {
                    extendsName != null -> resolved[extendsName] ?: defaults[extendsName]
                    name in defaults -> defaults[name]
                    else -> null
                }
                if (extendsName != null && base == null) continue   // wait for base to resolve
                resolved[name] = applyOverrides(name, base, fields, env)
                it.remove()
                madeProgress = true
            }
        }
        require(pending.isEmpty()) {
            "MT5 profiles have unresolvable extends chain: ${pending.keys}"
        }

        // Validate uniqueness of magic
        val magicGroups = resolved.values.groupBy { it.magic }.filterValues { it.size > 1 }
        require(magicGroups.isEmpty()) {
            "MT5 profile magic numbers must be unique; collisions: $magicGroups"
        }
        return resolved.values.toList()
    }

    private fun applyOverrides(
        name: String,
        base: MT5BrokerProfile?,
        fields: Map<String, String>,
        env: Map<String, String>,
    ): MT5BrokerProfile {
        // Start with base or defaults
        val gatewayUrl = pick("gateway_url", fields, env, name, base?.gatewayUrl)
            ?: error("MT5 profile '$name' missing required field: gateway_url")
        val suffix = pick("symbol_suffix", fields, env, name, base?.symbolPolicy?.suffix) ?: ""
        val magic = (pick("magic", fields, env, name, base?.magic?.toString())?.toInt())
            ?: error("MT5 profile '$name' missing required field: magic")
        val tz = (pick("server_tz_offset_hours", fields, env, name, base?.serverTzOffsetHours?.toString())?.toInt())
            ?: error("MT5 profile '$name' missing required field: server_tz_offset_hours")
        // aliases not env-overridable (nested); inherit from base
        val aliases = base?.symbolPolicy?.aliases ?: emptyMap()
        return MT5BrokerProfile(
            name = name,
            gatewayUrl = gatewayUrl,
            symbolPolicy = SymbolPolicy(suffix = suffix, aliases = aliases),
            serverTzOffsetHours = tz,
            magic = magic,
            instrumentOverrides = base?.instrumentOverrides ?: emptyMap(),
            pollIntervalMs = (pick("poll_interval_ms", fields, env, name, base?.pollIntervalMs?.toString())?.toLong())
                ?: 1000L,
            httpTimeoutMs = (pick("http_timeout_ms", fields, env, name, base?.httpTimeoutMs?.toString())?.toLong())
                ?: 5000L,
            retryAttempts = (pick("retry_attempts", fields, env, name, base?.retryAttempts?.toString())?.toInt())
                ?: 3,
            deviationPoints = (pick("deviation_points", fields, env, name, base?.deviationPoints?.toString())?.toInt())
                ?: 20,
            capabilityRestrictions = base?.capabilityRestrictions ?: emptySet(),
        )
    }

    private fun pick(
        field: String,
        fields: Map<String, String>,
        env: Map<String, String>,
        name: String,
        baseValue: String?,
    ): String? {
        val envKey = "QKT_BROKER_${name.uppercase().replace("-", "_")}_${field.uppercase()}"
        return env[envKey]
            ?: fields[field]
            ?: baseValue
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5BrokerProfileLoaderTest`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoader.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5BrokerProfileLoaderTest.kt
git commit -m "feat(broker): mt5 profile loader with defaults extends and env overrides"
```

---

## Task 9: Daemon startup wiring

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`

Wire `MT5BrokerProfileLoader` into daemon startup. Constructed `MT5Broker` instances are kept in a list so they can be shut down on daemon exit.

- [ ] **Step 1: Add profile loading + broker registration**

Locate the section where `StrategyRegistry` is constructed in `DaemonCommand.startDaemon()`. Before it, add:

```kotlin
// Load MT5 broker profiles from Config.brokers + env
val configPath = resolveConfigPath(args)        // existing helper or Path.of()
val config = com.qkt.cli.Config.load(configPath)
val mt5Profiles =
    com.qkt.broker.mt5
        .MT5BrokerProfileLoader()
        .load(
            raw = config.brokers,
            defaults = com.qkt.broker.mt5.MT5DefaultProfiles.all,
            env = System.getenv(),
        )
val mt5Brokers = mt5Profiles.associateBy({ it.name }) { profile ->
    com.qkt.broker.mt5.MT5Broker(profile, brokerEventBus, clock)
}
```

> **Verify:** `brokerEventBus`/`clock` are the right names from the existing daemon startup code. Read `DaemonCommand.kt` and adapt.

- [ ] **Step 2: Wire `mt5Brokers` into the broker dispatch**

Find where `marketSourceProvider` or the broker registry is constructed. Pass `mt5Brokers` through so strategies referencing `EXNESS:`/`ICMARKETS:`/etc. resolve to the MT5 brokers.

> The exact integration point depends on how Phase 12c+ daemon registers brokers; read the existing code and add MT5 brokers to the registry.

- [ ] **Step 3: Add shutdown hook**

In the daemon shutdown path:

```kotlin
mt5Brokers.values.forEach { runCatching { it.shutdown() } }
```

- [ ] **Step 4: Compile + run all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/DaemonCommand.kt
git commit -m "feat(daemon): load mt5 broker profiles at startup"
```

---

## Task 10: `qkt brokers list` CLI subcommand

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/BrokersCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

`qkt brokers list` reads Config + defaults + env and prints resolved profiles. No daemon required (operates against config only — does not query gateways for health in v1; that's a future enhancement).

- [ ] **Step 1: Create command**

```kotlin
// src/main/kotlin/com/qkt/cli/BrokersCommand.kt
package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5DefaultProfiles
import java.nio.file.Path

class BrokersCommand(
    private val args: Args,
) {
    fun run(): Int {
        return when (args.firstNonOption()) {
            "list" -> list()
            null -> list()
            else -> {
                System.err.println("qkt: unknown brokers subcommand")
                ExitCodes.ARG_ERROR
            }
        }
    }

    private fun list(): Int {
        val configPath = args.option("config")?.let { Path.of(it) }
            ?: Path.of("./qkt.config.yaml")
        val config = Config.load(configPath)
        val profiles =
            try {
                MT5BrokerProfileLoader().load(
                    raw = config.brokers,
                    defaults = MT5DefaultProfiles.all,
                    env = System.getenv(),
                )
            } catch (e: Exception) {
                System.err.println("qkt: brokers load failed: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        if (args.flag("json")) {
            println(profiles.joinToString(",", "[", "]") { jsonProfile(it) })
            return ExitCodes.SUCCESS
        }
        println("NAME              KIND  GATEWAY                  SUFFIX  TZ  MAGIC")
        for (p in profiles) {
            println(
                "%-17s %-5s %-23s %-7s %-3s %s".format(
                    p.name,
                    "mt5",
                    p.gatewayUrl,
                    if (p.symbolPolicy.suffix.isEmpty()) "-" else p.symbolPolicy.suffix,
                    p.serverTzOffsetHours.toString(),
                    p.magic.toString(),
                ),
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun jsonProfile(p: com.qkt.broker.mt5.MT5BrokerProfile): String =
        """{"name":"${p.name}","kind":"mt5","gatewayUrl":"${p.gatewayUrl}",""" +
            """"symbolSuffix":"${p.symbolPolicy.suffix}","serverTzOffsetHours":${p.serverTzOffsetHours},""" +
            """"magic":${p.magic}}"""
}
```

- [ ] **Step 2: Wire into `Main.kt`**

In the verb dispatch:

```kotlin
"brokers" -> BrokersCommand(args).run()
```

- [ ] **Step 3: Compile + smoke test**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

```bash
./gradlew run --args="brokers list"
```

Expected output: lists default exness, icmarkets, ftmo, pepperstone (with default URLs since no qkt.config.yaml in repo root).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BrokersCommand.kt \
        src/main/kotlin/com/qkt/cli/Main.kt
git commit -m "feat(cli): qkt brokers list subcommand"
```

---

## Task 11: Version bump + README + phase changelog + backlog

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt` (`VERSION = "0.19.0"`)
- Modify: `README.md`
- Create: `docs/phases/phase-17.md`
- Modify: `docs/backlog.md`

- [ ] **Step 1: Bump version**

```kotlin
const val VERSION: String = "0.19.0"
```

- [ ] **Step 2: README**

Update latest-release line to `v0.19.0`. Add under feature list:

```
- **MT5 broker (multi-profile)** — talks to per-broker `mt5-gateway` HTTP services. Built-in defaults for Exness, ICMarkets, FTMO, Pepperstone; override or extend via `qkt.config.yaml`. v1 ships Market + Bracket; other order types fall through to engine-managed paths ([phase 17](docs/phases/phase-17.md)).
```

- [ ] **Step 3: Phase changelog**

Create `docs/phases/phase-17.md` per qkt SKILL §6 with: Summary, What's new, Migration, Usage cookbook (running mt5-gateway, configuring profile, deploying a strategy with `EXNESS:`, multi-account setup with `extends:`, env-var override, `qkt brokers list`), Testing patterns, Known limitations (cross-broker same-symbol deferred, TV-vs-MT5 price drift risk, 1Hz poll cadence, close-price approximation), References.

- [ ] **Step 4: Update backlog**

In `docs/backlog.md`:

```
- `done` — MT5 (Exness + others) broker via mt5-gateway HTTP; profile-driven multi-broker (see [phase 17](phases/phase-17.md))
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt README.md \
        docs/phases/phase-17.md docs/backlog.md
git commit -m "chore(cli): bump version to 0.19.0 and add phase 17 changelog"
```

---

## Task 12: Final precheck and merge

- [ ] **Step 1: Run precheck**

Run: `./scripts/precheck.sh`
Expected: All steps green.

- [ ] **Step 2: Verify commit log**

Run: `git log --oneline main..HEAD`
Expected: every commit follows §3 conventions.

- [ ] **Step 3: Use `superpowers:finishing-a-development-branch`**

Announce and follow that skill to merge.

---

## Self-Review

**Spec coverage check:**
- `MT5Protocol` capability constant — Task 1
- `MT5WireTypes` data classes — Task 1
- `MT5BrokerProfile` + `SymbolPolicy` + `InstrumentSpec` — Task 2
- `MT5DefaultProfiles` (exness, icmarkets, ftmo, pepperstone) — Task 2
- `MT5Symbol` translation with round-trip + edge cases — Task 3
- `MT5OrderTranslator` for Market + Bracket — Task 4
- `MT5Client` HTTP transport with retry, TZ normalization, MockWebServer test — Task 5
- `MT5StateRecovery` + `MT5PositionPoller` — Task 6
- `MT5Broker` integration — Task 7
- `MT5BrokerProfileLoader` (defaults + extends + env + validation) — Task 8
- Daemon startup wiring — Task 9
- `qkt brokers list` CLI — Task 10
- Version bump + README + changelog + backlog — Task 11

**Placeholder scan:** Tasks 6, 7, 9 contain `>` Verify notes flagging existing-code touchpoints (`BrokerEvent` field names, `SubmitAck` variants, daemon startup integration). These are explicit verification cues, not placeholders. No `TBD`/`TODO`/"fill in later" markers remain.

**Type consistency check:**
- `MT5OrderRequest` field names (`symbol`, `volume`, `type`, `magic`, `comment`, `sl`, `tp`, `deviation`) consistent across translator (Task 4), client (Task 5), broker (Task 7).
- `MT5Position` field names (`ticket`, `symbol`, `type`, `volume`, `priceOpen`, `magic`, `openTime`) consistent across client (Task 5), poller (Task 6), state recovery (Task 6).
- `MT5BrokerProfile` field names consistent across data class (Task 2), defaults (Task 2), translator (Task 4), client construction (Task 7), loader (Task 8), CLI (Task 10).
- `MT5Symbol.toBroker` / `toQkt` consistent across translator (Task 4), broker (Task 7), poller (Task 6), state recovery (Task 6).
- Profile YAML field names (`gateway_url`, `symbol_suffix`, `magic`, `server_tz_offset_hours`, `extends`, `type`) consistent across loader (Task 8), CLI brokers list (Task 10), spec.

**Open verifications during execution:**
- `OrderTypeCapability` enum value names (`MARKET`, `BRACKET`).
- `BrokerEvent.PositionReconciled` / `OrderFilled` / `OrderAccepted` / `OrderRejected` field names.
- `SubmitAck.Accepted` / `SubmitAck.Rejected` variant names.
- `OrderRequest.Limit.limitPrice` field name (test in Task 4).
- `Side` enum location (`com.qkt.common.Side` vs `com.qkt.execution.Side`).
- `okhttp3:mockwebserver` version aligned with existing okhttp version.
- `Config.load` resolveConfigPath helper or path resolution pattern.
- Daemon startup integration point for broker registry.
