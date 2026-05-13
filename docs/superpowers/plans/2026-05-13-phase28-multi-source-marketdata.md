# Phase 28 — Multi-source market data · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote the parity harness's MT5 HTTP clients to a real `Mt5MarketSource`, add a new `BybitSpotMarketSource` + `BybitLinearMarketSource` (public WS for ticks, REST for klines), and wire them into a `CompositeMarketSource` at daemon boot. Strategies addressing `EXNESS:XAUUSD` automatically read from the MT5 gateway; `BYBIT_SPOT:BTCUSDT` reads from Bybit's public WS; everything else falls through to TradingView.

**Architecture:** Three new `MarketSource` implementations under `com.qkt.marketdata.live.{mt5, bybit}`. Composite routing by symbol prefix matches the broker profile name (uppercased). Zero DSL change, zero new config schema — existing `brokers:` section drives both broker and data side. The MT5 source is HTTP-polled (50ms default); the Bybit sources are WS-driven with exponential-backoff reconnect.

**Tech stack:** Kotlin 1.9, JUnit 5 + AssertJ, OkHttp WebSocket + MockWebServer, kotlinx.serialization.json. No new dependencies. ~1500 LOC across ~22 tasks. Estimated 4-5 focused days.

> Read the spec first: `docs/superpowers/specs/2026-05-13-phase28-multi-source-marketdata-design.md`.

---

### Task 1 — Recon: read the existing data-source surface

**Files:**
- Read: `src/main/kotlin/com/qkt/marketdata/source/MarketSource.kt`
- Read: `src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt`
- Read: `src/main/kotlin/com/qkt/marketdata/source/MarketSourceCapability.kt`
- Read: `src/main/kotlin/com/qkt/marketdata/live/LiveTickSource.kt`
- Read: `src/main/kotlin/com/qkt/marketdata/live/LiveTickFeed.kt`
- Read: `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSource.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfile.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5Symbol.kt`
- Read: `src/main/kotlin/com/qkt/tools/parity/Mt5DataClient.kt`
- Read: `src/main/kotlin/com/qkt/tools/parity/Mt5TickClient.kt`
- Read: `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt` (for HTTP signing patterns)
- Read: `src/main/kotlin/com/qkt/cli/Config.kt` (broker profile loading)
- Read: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt:36-95` (where MT5 profiles are loaded)

- [ ] Baseline green: `./gradlew test --no-daemon` passes.
- [ ] Note in `findings/phase28-recon.md`: which symbol-policy helper to reuse, what `LiveTickFeed.LiveTickSource` interface expects, and how `TradingViewMarketSource.connect()` integrates `TradingViewWebSocket`. (Findings file may live outside the repo if you prefer.)

---

### Task 2 — Skeleton: new packages

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/mt5/.gitkeep` (or first source file)
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/.gitkeep` (or first source file)

- [ ] Create the two packages. No code yet; the first file in each comes in Task 3 and Task 11.
- [ ] Verify path: `find src/main/kotlin/com/qkt/marketdata/live -type d` shows `live/mt5` and `live/bybit`.

---

### Task 3 — Move `Mt5DataClient` from `tools/parity/` to the marketdata package

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5DataClient.kt`
- Modify: `src/main/kotlin/com/qkt/tools/parity/ParityBarsXauusd.kt` (update import)
- Delete: `src/main/kotlin/com/qkt/tools/parity/Mt5DataClient.kt`

- [ ] Move the file verbatim. Change `package com.qkt.tools.parity` → `package com.qkt.marketdata.live.mt5`.
- [ ] Update import in `ParityBarsXauusd.kt`:

```kotlin
import com.qkt.marketdata.live.mt5.Mt5DataClient
```

- [ ] Run: `./gradlew compileKotlin --no-daemon` — must succeed.
- [ ] Commit: `refactor(marketdata): move Mt5DataClient from tools/parity into live/mt5`.

---

### Task 4 — Move `Mt5TickClient` from `tools/parity/` to the marketdata package

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5TickClient.kt`
- Modify: `src/main/kotlin/com/qkt/tools/parity/ParityTicksXauusd.kt` (update import)
- Delete: `src/main/kotlin/com/qkt/tools/parity/Mt5TickClient.kt`

- [ ] Move verbatim. Change package.
- [ ] Update import in `ParityTicksXauusd.kt`:

```kotlin
import com.qkt.marketdata.live.mt5.Mt5TickClient
```

- [ ] `./gradlew compileKotlin --no-daemon` succeeds.
- [ ] Commit: `refactor(marketdata): move Mt5TickClient from tools/parity into live/mt5`.

---

### Task 5 — `Mt5TickFeedSource` (LiveTickSource impl wrapping the poll loop)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSource.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSourceTest.kt`

- [ ] Write the failing test first:

```kotlin
package com.qkt.marketdata.live.mt5

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5TickFeedSourceTest {
    @Test
    fun `polls MT5 gateway and emits ticks deduped by time_msc`() {
        val server = MockWebServer().apply { start() }
        try {
            // 3 responses: same time_msc twice (dedup), then new
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"bid":4700.0,"ask":4700.3,"last":4700.1,"flags":6,"time":1778662794,"time_msc":1778662794911,"volume":0,"volume_real":0}"""))
            }
            server.enqueue(MockResponse().setBody("""{"bid":4701.0,"ask":4701.3,"last":4701.1,"flags":6,"time":1778662795,"time_msc":1778662795200,"volume":0,"volume_real":0}"""))

            val source = Mt5TickFeedSource(
                baseUrl = server.url("/").toString().trimEnd('/'),
                symbols = listOf("XAUUSDm"),
                pollIntervalMs = 5L,
                http = OkHttpClient(),
            )
            val captured = mutableListOf<com.qkt.marketdata.Tick>()
            source.start(onTick = { captured.add(it) }, onError = {}, onDisconnect = {})
            // give the poller a tick or two
            Thread.sleep(80)
            source.stop()
            assertThat(captured).hasSize(2)
            assertThat(captured.map { it.price.toPlainString() }).containsExactly("4700.10000000", "4701.10000000")
        } finally {
            server.shutdown()
        }
    }
}
```

- [ ] Run: `./gradlew test --tests Mt5TickFeedSourceTest` — fails (class doesn't exist).

- [ ] Implement:

```kotlin
package com.qkt.marketdata.live.mt5

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

class Mt5TickFeedSource(
    private val baseUrl: String,
    private val symbols: List<String>,
    private val pollIntervalMs: Long = 50L,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
) : LiveTickSource {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "Mt5TickFeedSource already started" }
        val client = Mt5TickClient(baseUrl, http)
        thread = Thread({
            val lastByteSymbol = mutableMapOf<String, Long>()
            while (running.get()) {
                for (sym in symbols) {
                    try {
                        val deadline = System.currentTimeMillis() + 1
                        client.pollUntil(symbol = sym, deadlineMs = deadline, intervalMs = 0L) { t ->
                            if ((lastByteSymbol[sym] ?: 0L) < t.brokerTimeMs) {
                                lastByteSymbol[sym] = t.brokerTimeMs
                                onTick(
                                    Tick(
                                        symbol = sym,
                                        price = t.last.setScale(Money.SCALE, Money.ROUNDING),
                                        timestamp = clock.now(),
                                        bid = t.bid.setScale(Money.SCALE, Money.ROUNDING),
                                        ask = t.ask.setScale(Money.SCALE, Money.ROUNDING),
                                        volume = null,
                                    ),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
                Thread.sleep(pollIntervalMs)
            }
            onDisconnect()
        }, "mt5-tick-feed").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}
```

- [ ] Run: `./gradlew test --tests Mt5TickFeedSourceTest` — passes.

- [ ] Commit: `feat(marketdata): Mt5TickFeedSource — polling LiveTickSource wrapper`.

---

### Task 6 — `Mt5BarFetcher` (range bar fetch)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5BarFetcher.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5BarFetcherTest.kt`

- [ ] Write the failing test:

```kotlin
package com.qkt.marketdata.live.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5BarFetcherTest {
    @Test
    fun `fetchRange hits fetch_data_range and parses bars`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":4700.5,"high":4701,"low":4699,"open":4700,"tick_volume":100,"time":"2026-05-13T08:00:00Z"}]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))
            val candles = fetcher.fetchRange(
                symbol = "XAUUSDm",
                window = TimeWindow.parse("5m"),
                range = TimeRange(
                    from = Instant.parse("2026-05-13T08:00:00Z"),
                    to = Instant.parse("2026-05-13T08:05:00Z"),
                ),
            ).toList()
            assertThat(candles).hasSize(1)
            assertThat(candles.first().close.toPlainString()).isEqualTo("4700.5")
            assertThat(candles.first().startTime).isEqualTo(Instant.parse("2026-05-13T08:00:00Z").toEpochMilli())
            val request = server.takeRequest()
            assertThat(request.path).contains("/fetch_data_range")
            assertThat(request.path).contains("symbol=XAUUSDm")
            assertThat(request.path).contains("timeframe=M5")
        } finally {
            server.shutdown()
        }
    }
}
```

- [ ] Run: fails.

- [ ] Implement:

```kotlin
package com.qkt.marketdata.live.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request

class Mt5BarFetcher(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    fun fetchRange(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val tf = windowToTimeframe(window)
        val start = URLEncoder.encode(range.from.toString().removeSuffix("Z"), "UTF-8")
        val end = URLEncoder.encode(range.to.toString().removeSuffix("Z"), "UTF-8")
        val url = "$baseUrl/fetch_data_range?symbol=$symbol&timeframe=$tf&start=$start&end=$end"
        val client = Mt5DataClient(baseUrl, http)
        return client.parseFromUrl(url, symbol, tf).asSequence()
    }

    private fun windowToTimeframe(window: TimeWindow): String =
        when (window.durationMs) {
            60_000L -> "M1"
            300_000L -> "M5"
            900_000L -> "M15"
            3_600_000L -> "H1"
            14_400_000L -> "H4"
            86_400_000L -> "D1"
            else -> error("Unsupported MT5 timeframe: ${window.durationMs}ms")
        }
}
```

- [ ] Expose `Mt5DataClient.parseFromUrl(url, symbol, tf)` as `internal` (or add it as a public helper that returns `List<Candle>`).
- [ ] Run: passes.
- [ ] Commit: `feat(marketdata): Mt5BarFetcher — TimeRange-based bar fetch`.

---

### Task 7 — `Mt5MarketSource` (implements MarketSource)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5MarketSource.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5MarketSourceTest.kt`

- [ ] Test (symbol policy + supports + dispatch):

```kotlin
package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5MarketSourceTest {
    private val exness =
        MT5BrokerProfile(
            name = "exness",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = "m"),
            magic = 123L,
            httpTimeoutMs = 5_000L,
            retryAttempts = 1,
            pollIntervalMs = 50L,
        )

    @Test
    fun `supports only its own prefix`() {
        val src = Mt5MarketSource(exness)
        assertThat(src.supports("EXNESS:XAUUSD")).isTrue
        assertThat(src.supports("LATCH:XAUUSD")).isFalse
        assertThat(src.supports("BYBIT_SPOT:BTCUSDT")).isFalse
        assertThat(src.supports("OANDA:XAUUSD")).isFalse
        assertThat(src.supports("XAUUSD")).isFalse
    }

    @Test
    fun `declares LIVE_TICKS and BARS capabilities`() {
        val src = Mt5MarketSource(exness)
        assertThat(src.capabilities)
            .containsExactlyInAnyOrder(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)
    }

    @Test
    fun `name encodes the profile`() {
        assertThat(Mt5MarketSource(exness).name).isEqualTo("MT5:exness")
    }
}
```

- [ ] Run: fails.

- [ ] Implement:

```kotlin
package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import okhttp3.OkHttpClient

class Mt5MarketSource(
    private val profile: MT5BrokerProfile,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = SystemClock(),
) : MarketSource, AutoCloseable {
    override val name: String = "MT5:${profile.name}"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    private val prefix: String = "${profile.name.uppercase()}:"

    override fun supports(symbol: String): Boolean = symbol.startsWith(prefix)

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all { supports(it) }) { "${name} cannot serve $symbols" }
        val wireSymbols = symbols.map { profile.symbolPolicy.toWireSymbol(stripPrefix(it)) }
        return LiveTickFeed(
            source = Mt5TickFeedSource(
                baseUrl = profile.gatewayUrl,
                symbols = wireSymbols,
                pollIntervalMs = profile.pollIntervalMs,
                http = http,
                clock = clock,
            ),
            queueCapacity = 10_000,
        )
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        require(supports(symbol)) { "${name} cannot serve $symbol" }
        val wire = profile.symbolPolicy.toWireSymbol(stripPrefix(symbol))
        return Mt5BarFetcher(profile.gatewayUrl, http).fetchRange(wire, window, range)
    }

    override fun close() {}

    private fun stripPrefix(symbol: String): String = symbol.removePrefix(prefix)
}
```

- [ ] Confirm `SymbolPolicy.toWireSymbol` exists in `com.qkt.broker.mt5` — if not, add it.
- [ ] Run: passes.
- [ ] Commit: `feat(marketdata): Mt5MarketSource implements MarketSource over MT5 gateway`.

---

### Task 8 — `Mt5MarketSource` end-to-end with `LiveTickFeed`

**Files:**
- Modify: `src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5MarketSourceTest.kt` (add test)

- [ ] Add test that consumes from the `TickFeed`:

```kotlin
@Test
fun `liveTicks returns a TickFeed that yields broker ticks`() {
    val server = MockWebServer().apply { start() }
    try {
        server.enqueue(
            MockResponse().setBody(
                """{"bid":4700.0,"ask":4700.3,"last":4700.1,"flags":6,"time":1778662794,"time_msc":1778662794911,"volume":0,"volume_real":0}""",
            ),
        )
        val profile = exness.copy(gatewayUrl = server.url("/").toString().trimEnd('/'), pollIntervalMs = 5L)
        val source = Mt5MarketSource(profile)
        val feed = source.liveTicks(listOf("EXNESS:XAUUSD"))
        val tick = feed.next() // blocks
        assertThat(tick).isNotNull
        assertThat(tick!!.symbol).isEqualTo("XAUUSDm")
        feed.close()
    } finally {
        server.shutdown()
    }
}
```

- [ ] Run: passes.
- [ ] Commit: `test(marketdata): Mt5MarketSource end-to-end TickFeed flow`.

---

### Task 9 — Bybit public WS frame parser (`BybitPublicFrame`)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitPublicFrame.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/bybit/BybitPublicFrameTest.kt`

- [ ] Bybit public WS frames look like:

  - `tickers.BTCUSDT` push: `{"topic":"tickers.BTCUSDT","type":"snapshot","data":{"symbol":"BTCUSDT","bid1Price":"60000.0","ask1Price":"60000.5","lastPrice":"60000.2","volume24h":"123"}}`
  - `publicTrade.BTCUSDT` push: `{"topic":"publicTrade.BTCUSDT","type":"snapshot","data":[{"S":"Buy","p":"60000.2","v":"0.5","T":1778662794911}]}`
  - subscribe ack: `{"success":true,"ret_msg":"","op":"subscribe","conn_id":"..."}`

- [ ] Write the failing test:

```kotlin
package com.qkt.marketdata.live.bybit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitPublicFrameTest {
    @Test
    fun `parses tickers frame`() {
        val raw = """{"topic":"tickers.BTCUSDT","type":"snapshot","data":{"symbol":"BTCUSDT","bid1Price":"60000.0","ask1Price":"60000.5","lastPrice":"60000.2"}}"""
        val frame = BybitPublicFrame.parse(raw) as BybitPublicFrame.Tickers
        assertThat(frame.symbol).isEqualTo("BTCUSDT")
        assertThat(frame.bid.toPlainString()).isEqualTo("60000.0")
        assertThat(frame.ask.toPlainString()).isEqualTo("60000.5")
        assertThat(frame.last.toPlainString()).isEqualTo("60000.2")
    }

    @Test
    fun `parses publicTrade frame`() {
        val raw = """{"topic":"publicTrade.BTCUSDT","type":"snapshot","data":[{"S":"Buy","p":"60000.2","v":"0.5","T":1778662794911}]}"""
        val frame = BybitPublicFrame.parse(raw) as BybitPublicFrame.Trade
        assertThat(frame.symbol).isEqualTo("BTCUSDT")
        assertThat(frame.price.toPlainString()).isEqualTo("60000.2")
        assertThat(frame.volume.toPlainString()).isEqualTo("0.5")
        assertThat(frame.brokerTimeMs).isEqualTo(1778662794911L)
    }

    @Test
    fun `parses subscribe ack`() {
        val raw = """{"success":true,"ret_msg":"","op":"subscribe","conn_id":"abc"}"""
        val frame = BybitPublicFrame.parse(raw)
        assertThat(frame).isInstanceOf(BybitPublicFrame.SubscribeAck::class.java)
    }
}
```

- [ ] Run: fails.

- [ ] Implement (sealed class with `parse`, similar shape to `TradingViewFrame`):

```kotlin
package com.qkt.marketdata.live.bybit

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class BybitPublicFrame {
    data class Tickers(val symbol: String, val bid: BigDecimal, val ask: BigDecimal, val last: BigDecimal) : BybitPublicFrame()

    data class Trade(val symbol: String, val price: BigDecimal, val volume: BigDecimal, val brokerTimeMs: Long) : BybitPublicFrame()

    data class SubscribeAck(val success: Boolean, val message: String) : BybitPublicFrame()

    data class Unknown(val raw: String) : BybitPublicFrame()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): BybitPublicFrame {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Unknown(text)
            obj["op"]?.let {
                return SubscribeAck(
                    success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                    message = obj["ret_msg"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            val topic = obj["topic"]?.jsonPrimitive?.content ?: return Unknown(text)
            return when {
                topic.startsWith("tickers.") -> parseTickers(topic, obj)
                topic.startsWith("publicTrade.") -> parseTrade(topic, obj)
                else -> Unknown(text)
            }
        }

        private fun parseTickers(topic: String, obj: JsonObject): BybitPublicFrame {
            val data = obj["data"]?.jsonObject ?: return Unknown(obj.toString())
            return Tickers(
                symbol = topic.removePrefix("tickers."),
                bid = data["bid1Price"]!!.jsonPrimitive.content.toBigDecimal(),
                ask = data["ask1Price"]!!.jsonPrimitive.content.toBigDecimal(),
                last = data["lastPrice"]!!.jsonPrimitive.content.toBigDecimal(),
            )
        }

        private fun parseTrade(topic: String, obj: JsonObject): BybitPublicFrame {
            val arr = obj["data"]?.jsonArray ?: return Unknown(obj.toString())
            val first = arr.firstOrNull()?.jsonObject ?: return Unknown(obj.toString())
            return Trade(
                symbol = topic.removePrefix("publicTrade."),
                price = first["p"]!!.jsonPrimitive.content.toBigDecimal(),
                volume = first["v"]!!.jsonPrimitive.content.toBigDecimal(),
                brokerTimeMs = first["T"]!!.jsonPrimitive.content.toLong(),
            )
        }
    }
}
```

- [ ] Run: passes.
- [ ] Commit: `feat(marketdata): BybitPublicFrame — sealed parser for public WS messages`.

---

### Task 10 — `BybitPublicWsClient` with reconnect

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitPublicWsClient.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/bybit/BybitPublicWsClientTest.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/live/bybit/FakeBybitWebSocket.kt` (helper)

- [ ] Define an interface mirror similar to `TradingViewWebSocketLike` so tests can drive frames:

```kotlin
interface BybitPublicWsLike {
    fun addListener(listener: BybitPublicListener)
    fun removeListener(listener: BybitPublicListener)
    fun send(text: String)
    fun close()
}

interface BybitPublicListener {
    fun onFrame(frame: BybitPublicFrame)
    fun onConnected()
    fun onDisconnected(reason: String)
}
```

- [ ] Test (covers: send subscribe on initial connect; replay subscribe on reconnect; do NOT double-subscribe on initial connect — same bug as TV QuoteSession):

```kotlin
@Test
fun `subscribe sends op_subscribe with tickers and publicTrade args`() {
    val fake = FakeBybitWebSocket()
    val client = BybitPublicWsClient(fake)
    client.subscribe(listOf("BTCUSDT"), onTick = {}, onDisconnect = {})
    assertThat(fake.sentTexts).hasSize(1)
    assertThat(fake.sentTexts[0]).contains("\"op\":\"subscribe\"")
    assertThat(fake.sentTexts[0]).contains("\"tickers.BTCUSDT\"")
    assertThat(fake.sentTexts[0]).contains("\"publicTrade.BTCUSDT\"")
}

@Test
fun `onConnected before any disconnect does not re-send subscribe`() {
    val fake = FakeBybitWebSocket()
    val client = BybitPublicWsClient(fake)
    client.subscribe(listOf("BTCUSDT"), onTick = {}, onDisconnect = {})
    val afterSubscribe = fake.sentTexts.size
    fake.simulateConnect()
    assertThat(fake.sentTexts.size).isEqualTo(afterSubscribe)
}

@Test
fun `reconnect resends subscribe`() {
    val fake = FakeBybitWebSocket()
    val client = BybitPublicWsClient(fake)
    client.subscribe(listOf("BTCUSDT"), onTick = {}, onDisconnect = {})
    val afterSubscribe = fake.sentTexts.size
    fake.simulateDisconnect("oops")
    fake.simulateConnect()
    assertThat(fake.sentTexts.size).isGreaterThan(afterSubscribe)
}

@Test
fun `qsd-equivalent emits Tick with bid ask price`() {
    val fake = FakeBybitWebSocket()
    val client = BybitPublicWsClient(fake)
    val captured = mutableListOf<com.qkt.marketdata.Tick>()
    client.subscribe(listOf("BTCUSDT"), onTick = { captured.add(it) }, onDisconnect = {})
    fake.deliver(
        """{"topic":"tickers.BTCUSDT","type":"snapshot","data":{"symbol":"BTCUSDT","bid1Price":"60000.0","ask1Price":"60000.5","lastPrice":"60000.2"}}""",
    )
    assertThat(captured).hasSize(1)
    assertThat(captured[0].bid?.toPlainString()).isEqualTo("60000.0")
    assertThat(captured[0].ask?.toPlainString()).isEqualTo("60000.5")
    assertThat(captured[0].price.toPlainString()).isEqualTo("60000.2")
}
```

- [ ] Run: fails.

- [ ] Implement `FakeBybitWebSocket` (mirrors `FakeTradingViewWebSocket`) and `BybitPublicWsClient`:

```kotlin
class BybitPublicWsClient(
    private val ws: BybitPublicWsLike,
    private val clock: Clock = SystemClock(),
) : BybitPublicListener {
    private val symbols = mutableListOf<String>()
    private var onTick: ((Tick) -> Unit)? = null
    private var onDisconnect: (() -> Unit)? = null
    private var hasDisconnected = false
    private val state = mutableMapOf<String, MutableMap<String, BigDecimal>>()

    fun subscribe(
        symbols: List<String>,
        onTick: (Tick) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        this.symbols.addAll(symbols)
        this.onTick = onTick
        this.onDisconnect = onDisconnect
        ws.addListener(this)
        sendSubscribe()
    }

    fun close() { ws.removeListener(this); ws.close() }

    override fun onFrame(frame: BybitPublicFrame) {
        when (frame) {
            is BybitPublicFrame.Tickers -> {
                val s = state.getOrPut(frame.symbol) { mutableMapOf() }
                s["bid"] = frame.bid; s["ask"] = frame.ask; s["last"] = frame.last
                emit(frame.symbol, brokerTimeMs = null)
            }
            is BybitPublicFrame.Trade -> {
                val s = state.getOrPut(frame.symbol) { mutableMapOf() }
                s["last"] = frame.price; s["volume"] = frame.volume
                emit(frame.symbol, brokerTimeMs = frame.brokerTimeMs)
            }
            else -> { /* ack / unknown ignored */ }
        }
    }

    override fun onConnected() {
        if (hasDisconnected && symbols.isNotEmpty()) sendSubscribe()
    }

    override fun onDisconnected(reason: String) {
        hasDisconnected = true
        onDisconnect?.invoke()
    }

    private fun emit(symbol: String, brokerTimeMs: Long?) {
        val s = state[symbol] ?: return
        val last = s["last"] ?: return
        onTick?.invoke(
            Tick(
                symbol = symbol,
                price = last.setScale(Money.SCALE, Money.ROUNDING),
                timestamp = brokerTimeMs ?: clock.now(),
                bid = s["bid"]?.setScale(Money.SCALE, Money.ROUNDING),
                ask = s["ask"]?.setScale(Money.SCALE, Money.ROUNDING),
                volume = s["volume"]?.setScale(Money.SCALE, Money.ROUNDING),
            ),
        )
    }

    private fun sendSubscribe() {
        val args = symbols.flatMap { listOf("tickers.$it", "publicTrade.$it") }
        val body = buildJsonObject {
            put("op", "subscribe")
            put("args", buildJsonArray { args.forEach { add(it) } })
        }
        ws.send(body.toString())
    }
}
```

- [ ] Run: passes.
- [ ] Commit: `feat(marketdata): BybitPublicWsClient with reconnect-safe subscribe`.

---

### Task 11 — `BybitPublicWs` (real OkHttp-backed `BybitPublicWsLike`)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitPublicWs.kt`

- [ ] Wrap OkHttp WebSocket. Pattern: `TradingViewWebSocket` is the reference. Heartbeat for Bybit: send `{"op":"ping"}` every 20s.

```kotlin
class BybitPublicWs(
    private val url: String,
    private val client: OkHttpClient = defaultClient(),
) : BybitPublicWsLike {
    private val listeners = CopyOnWriteArrayList<BybitPublicListener>()
    private val socket = AtomicReference<WebSocket?>(null)
    private val closed = AtomicBoolean(false)

    override fun addListener(l: BybitPublicListener) { listeners.add(l) }
    override fun removeListener(l: BybitPublicListener) { listeners.remove(l) }

    fun connect() {
        if (closed.get()) error("BybitPublicWs is closed")
        socket.set(client.newWebSocket(Request.Builder().url(url).build(), Inner()))
    }

    override fun send(text: String) {
        socket.get()?.send(text) ?: error("BybitPublicWs not connected")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.get()?.close(1000, "client close")
    }

    private inner class Inner : WebSocketListener() {
        override fun onOpen(ws: WebSocket, r: Response) {
            listeners.forEach { runCatching { it.onConnected() } }
        }
        override fun onMessage(ws: WebSocket, text: String) {
            val frame = BybitPublicFrame.parse(text)
            listeners.forEach { runCatching { it.onFrame(frame) } }
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            listeners.forEach { runCatching { it.onDisconnected("closed:$code:$reason") } }
        }
        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            listeners.forEach { runCatching { it.onDisconnected("failure:${t.message}") } }
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

        fun connect(url: String): BybitPublicWs = BybitPublicWs(url).apply { connect() }
    }
}
```

- [ ] No new test (covered transitively by Task 14 e2e).
- [ ] Commit: `feat(marketdata): BybitPublicWs OkHttp-backed WebSocket`.

---

### Task 12 — `BybitKlineClient` (REST bars)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitKlineClient.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/bybit/BybitKlineClientTest.kt`

- [ ] Bybit REST shape: `GET /v5/market/kline?category=spot&symbol=BTCUSDT&interval=5&start=ms&end=ms&limit=1000`
- [ ] Response: `{"retCode":0,"retMsg":"OK","result":{"category":"spot","symbol":"BTCUSDT","list":[["1778662200000","60000.0","60001.0","59999.0","60000.5","1.5","90000.5"], ...]}}`

  Each entry: `[startMs, open, high, low, close, volume, turnover]`. Note that Bybit returns newest-first; reverse for ascending order.

- [ ] Test with MockWebServer canned response. Verify symbol, interval, start/end, ordering, parsing.
- [ ] Implement; paginate when range > limit (max 1000 per request).
- [ ] Commit: `feat(marketdata): BybitKlineClient — REST kline fetch with pagination`.

---

### Task 13 — `BybitSpotMarketSource`

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitSpotMarketSource.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/bybit/BybitSpotMarketSourceTest.kt`

- [ ] Test: supports `BYBIT_SPOT:` prefix, capabilities = {LIVE_TICKS, BARS}, name = "Bybit:spot".
- [ ] Implement. `liveTicks` opens `BybitPublicWs(spotWsUrl)` and wraps in `BybitPublicWsClient`. The `TickFeed` queues via `LiveTickFeed`.
- [ ] Commit: `feat(marketdata): BybitSpotMarketSource for spot public data`.

---

### Task 14 — `BybitLinearMarketSource`

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitLinearMarketSource.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/live/bybit/BybitLinearMarketSourceTest.kt`

- [ ] Identical shape to `BybitSpotMarketSource` but:
  - WS URL: `wss://stream.bybit.com/v5/public/linear`
  - REST `category=linear`
  - Prefix: `BYBIT_PERP:`
  - Name: `"Bybit:linear"`
- [ ] Consider extracting a shared `BybitMarketSourceBase` that takes the URL/category/prefix as constructor params. Both Spot and Linear become 3-line subclasses.
- [ ] Commit: `feat(marketdata): BybitLinearMarketSource for perp public data`.

---

### Task 15 — `LiveTickFeed` adapter for `BybitPublicWsClient`

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/live/bybit/BybitSpotMarketSource.kt` (or shared base)

- [ ] The `LiveTickSource` interface expects `start(onTick, onError, onDisconnect)` + `stop()`. Wrap `BybitPublicWsClient` accordingly:

```kotlin
private class BybitLiveTickSource(
    private val wsFactory: () -> BybitPublicWs,
    private val symbols: List<String>,
    private val clock: Clock,
) : LiveTickSource {
    private var client: BybitPublicWsClient? = null
    private var ws: BybitPublicWs? = null

    override fun start(onTick: (Tick) -> Unit, onError: (Throwable) -> Unit, onDisconnect: () -> Unit) {
        val w = wsFactory()
        ws = w
        val c = BybitPublicWsClient(w, clock)
        client = c
        c.subscribe(symbols, onTick = onTick, onDisconnect = onDisconnect)
    }
    override fun stop() { client?.close(); ws?.close() }
}
```

- [ ] No standalone test (covered by `BybitSpotMarketSourceTest.liveTicks` integration).
- [ ] Commit: `feat(marketdata): wire BybitPublicWsClient into LiveTickSource`.

---

### Task 16 — `BybitBrokerProfile` (config shape for data side)

**Files:**
- Read: `src/main/kotlin/com/qkt/broker/bybit/` for existing profile shape (if any).
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitBrokerProfile.kt` (only if not already present)
- Modify: `src/main/kotlin/com/qkt/cli/Config.kt` (if profile loading needs extension)

- [ ] If a Bybit profile already exists with a `category: spot|linear` field, this task is just verifying it. If not, add minimal:

```kotlin
data class BybitBrokerProfile(
    val name: String,
    val category: BybitCategory, // SPOT, LINEAR
    val apiKey: String,
    val apiSecret: String,
    // public data doesn't need auth, but executing does
)

enum class BybitCategory { SPOT, LINEAR }
```

- [ ] Profile loader extracts `type: bybit`, `category: spot|linear` from `brokers:` config.
- [ ] Test: profile parses correctly from YAML.
- [ ] Commit: `feat(broker): BybitBrokerProfile distinguishes spot vs linear (data side reuse)` — only if shipping new code.

---

### Task 17 — `CompositeMarketSource` boot wiring in `DaemonCommand`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`
- Test: `src/test/kotlin/com/qkt/cli/DaemonCommandSourceWiringTest.kt`

- [ ] Replace `defaultTradingViewSource` with a composite-builder:

```kotlin
private fun buildMarketSource(
    mt5Profiles: List<MT5BrokerProfile>,
    bybitProfiles: List<BybitBrokerProfile>,
): MarketSource {
    val routes = mutableListOf<Pair<SymbolPattern, MarketSource>>()
    for (p in mt5Profiles) {
        routes.add(SymbolPattern.prefix("${p.name.uppercase()}:") to Mt5MarketSource(p))
    }
    val hasSpot = bybitProfiles.any { it.category == BybitCategory.SPOT }
    val hasLinear = bybitProfiles.any { it.category == BybitCategory.LINEAR }
    if (hasSpot) routes.add(SymbolPattern.prefix("BYBIT_SPOT:") to BybitSpotMarketSource())
    if (hasLinear) routes.add(SymbolPattern.prefix("BYBIT_PERP:") to BybitLinearMarketSource())
    return CompositeMarketSource(routes = routes, fallback = TradingViewMarketSource.connect())
}
```

- [ ] In `startDaemon`, replace `sourceFactory = ::defaultTradingViewSource` with `sourceFactory = { _ -> buildMarketSource(mt5Profiles, bybitProfiles) }`. The single composite is reused across strategies.
- [ ] Test: given 2 MT5 profiles + 1 Bybit-spot profile, `buildMarketSource(...).supports("EXNESS:XAUUSD")` is true, `supports("BYBIT_SPOT:BTCUSDT")` is true, `supports("OANDA:SPY")` is true (TV fallback regex).
- [ ] Run: passes.
- [ ] Commit: `feat(cli): wire CompositeMarketSource at daemon boot from broker profiles`.

---

### Task 18 — Same wiring in `RunCommand`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/RunCommand.kt`

- [ ] Mirror Task 17 in the one-shot `qkt run` path. Same builder, same composite.
- [ ] Run: existing `RunCommand` tests still pass.
- [ ] Commit: `feat(cli): wire CompositeMarketSource in qkt run path`.

---

### Task 19 — Update parity harness to import promoted classes

**Files:**
- Modify: `src/main/kotlin/com/qkt/tools/parity/ParityBarsXauusd.kt`
- Modify: `src/main/kotlin/com/qkt/tools/parity/ParityTicksXauusd.kt`

- [ ] Already done in Tasks 3 and 4 (imports updated). Verify after the Bybit additions that nothing else in `tools/parity/` is stale.
- [ ] Run: `./gradlew runParityBarsXauusd` (manual, requires SSH tunnel — verify locally before commit).
- [ ] Commit (only if changes): `chore(tools): parity harness uses promoted MT5 clients`.

---

### Task 20 — End-to-end composite test

**Files:**
- Create: `src/test/kotlin/com/qkt/marketdata/source/CompositePhase28RoutingTest.kt`

- [ ] Test:

```kotlin
@Test
fun `composite routes EXNESS to MT5 and BYBIT_SPOT to bybit and others to tv`() {
    val mt5Server = MockWebServer().apply { start() }
    val bybitServer = MockWebServer().apply { start() }  // REST only; WS faked separately
    try {
        // ... enqueue MT5 tick response, Bybit kline response
        val profile = MT5BrokerProfile(
            name = "exness",
            gatewayUrl = mt5Server.url("/").toString().trimEnd('/'),
            symbolPolicy = SymbolPolicy(suffix = "m"),
            magic = 1L, httpTimeoutMs = 5_000L, retryAttempts = 1, pollIntervalMs = 5L,
        )
        val mt5 = Mt5MarketSource(profile)
        val bybit = BybitSpotMarketSource(/* mocked deps */)
        val tv = /* a stub MarketSource that captures calls */
        val composite = CompositeMarketSource(
            routes = listOf(
                SymbolPattern.prefix("EXNESS:") to mt5,
                SymbolPattern.prefix("BYBIT_SPOT:") to bybit,
            ),
            fallback = tv,
        )

        // routing assertions:
        assertThat(composite.supports("EXNESS:XAUUSD")).isTrue
        assertThat(composite.supports("BYBIT_SPOT:BTCUSDT")).isTrue
        assertThat(composite.supports("OANDA:SPY")).isTrue
    } finally { mt5Server.shutdown(); bybitServer.shutdown() }
}
```

- [ ] Run: passes.
- [ ] Commit: `test(marketdata): composite routes EXNESS to MT5, BYBIT_SPOT to bybit, others to TV`.

---

### Task 21 — Phase 28 changelog

**Files:**
- Create: `docs/phases/phase-28-multi-source-marketdata.md`

- [ ] Per qkt §6, every changelog must contain: Summary, What's new, Migration, Usage cookbook (multiple examples), Testing patterns, Known limitations, References.
- [ ] Migration section must prominently flag: **`EXNESS:XAUUSD` in existing strategies will read from MT5 after Phase 28 if an `exness` broker profile is configured. Previously fell through to TV.**
- [ ] Cookbook examples:
  - Strategy using EXNESS:XAUUSD (no DSL change needed)
  - Strategy using BYBIT_SPOT:BTCUSDT
  - Strategy using BYBIT_PERP:BTCUSDT
  - Strategy mixing two sources in one config (Bybit + MT5 in a portfolio)
  - Operator override: removing a broker profile to fall through to TV
- [ ] Commit: `docs: phase 28 changelog — multi-source market data`.

---

### Task 22 — Pre-merge verification

- [ ] `./gradlew build --no-daemon` green.
- [ ] `./gradlew ktlintCheck --no-daemon` green.
- [ ] Run parity harness once with `runParityBarsXauusd` to confirm the refactor didn't break it. (Requires SSH tunnel; if not available, skip with a note.)
- [ ] Manually verify on a deployment with `exness` profile that the daemon successfully boots and a sample strategy reads MT5 data (assert via `qkt status <name>` shows ticks flowing, or `qkt logs <name>` shows MT5 source name in startup log).
- [ ] Update the phase changelog if any task discovered a wrinkle that should be documented.
- [ ] Open PR per qkt §5 with the description template. Title: `[phase 28] feat: multi-source market data — MT5 and Bybit-WS as MarketSources`.

---

## Out-of-band items the plan does NOT cover

- **WS-based MT5 source.** Only HTTP polling is implemented. If `mt5-gateway` later adds a streaming endpoint, drop `Mt5TickFeedSource` and write `Mt5StreamingTickSource`.
- **Connection pooling.** Each strategy gets its own WS to Bybit. Acceptable at current scale.
- **Bybit private WS data.** Out of scope. Position/execution events come through the broker code path.
- **Asset-class abstraction / DSL `FROM` keyword.** Punted to a future phase if needed.
- **Reconnect-supervisor unification** — Bybit, TV, and broker-side Bybit each have their own; deduping into `com.qkt.common.ReconnectSupervisor` is a separate cleanup.
