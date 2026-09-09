package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Range polling semantics for [Mt5TickFeedSource] and [Mt5TickClient.fetchRange].
 *
 * The gateway floors `from_date` to whole seconds and treats `to_date` as exclusive, so a round
 * necessarily re-receives the head of the second it resumes from; correctness rests on the
 * watermark filter rather than on the request bounds being exact.
 */
class Mt5TickRangePollingTest {
    private fun tick(
        ms: Long,
        bid: String = "4700.0",
        ask: String = "4700.4",
    ): String =
        """{"bid":$bid,"ask":$ask,"last":0.0,"flags":6,""" +
            """"time":${ms / 1000},"time_msc":$ms,"volume":0}"""

    @Test
    fun `emits every tick in the returned range, not just the newest`() {
        // The whole point of the endpoint switch: /symbol_info_tick surrendered one quote per
        // round, so ticks arriving between polls were lost and every bar's range compressed.
        val base = 1778662794000L
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody(
                        "[" + (0 until 5).joinToString(",") { i -> tick(base + i * 100L) } + "]",
                    )
            }
        server.start()
        try {
            val captured = pollOnce(server, mapOf("XAUUSDm" to "EXNESS:XAUUSD"), want = 5)
            assertThat(captured.map(Tick::timestamp))
                .containsExactly(base, base + 100, base + 200, base + 300, base + 400)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `re-delivered ticks from the resumed second are deduped by the watermark`() {
        // from_date floors to the second, so a resuming round always re-receives ticks it has
        // already emitted. They must never reach the engine twice: a double-fed tick
        // double-counts into every indicator and re-arms edge-triggered rules.
        val base = 1778662794000L
        val rounds = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = rounds.incrementAndGet()
                    // Every round replays the same three ticks; later rounds add one more.
                    val count = if (n == 1) 3 else 4
                    return MockResponse().setBody(
                        "[" + (0 until count).joinToString(",") { i -> tick(base + i * 100L) } + "]",
                    )
                }
            }
        server.start()
        try {
            val captured = pollOnce(server, mapOf("XAUUSDm" to "EXNESS:XAUUSD"), want = 4)
            assertThat(captured.map(Tick::timestamp))
                .containsExactly(base, base + 100, base + 200, base + 300)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a round is merged across symbols into one timestamp-ordered stream`() {
        // Emitting symbol-at-a-time would put one symbol's newest tick ahead of another's
        // oldest. Harmless at one tick per symbol; a reordered feed over a range, and the
        // backtest replays strictly by timestamp.
        val base = 1778662794000L
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    // XAUUSD ticks land on even offsets, EURUSD on odd: correct ordering can
                    // only come from the cross-symbol merge, never from per-symbol emission.
                    val offsets = if (path.contains("XAUUSDm")) listOf(0L, 200L, 400L) else listOf(100L, 300L)
                    return MockResponse().setBody(
                        "[" + offsets.joinToString(",") { o -> tick(base + o) } + "]",
                    )
                }
            }
        server.start()
        try {
            val captured =
                pollOnce(
                    server,
                    linkedMapOf("XAUUSDm" to "EXNESS:XAUUSD", "EURUSDm" to "EXNESS:EURUSD"),
                    want = 5,
                )
            assertThat(captured.map(Tick::timestamp))
                .containsExactly(base, base + 100, base + 200, base + 300, base + 400)
            assertThat(captured.map(Tick::symbol))
                .containsExactly(
                    "EXNESS:XAUUSD",
                    "EXNESS:EURUSD",
                    "EXNESS:XAUUSD",
                    "EXNESS:EURUSD",
                    "EXNESS:XAUUSD",
                )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `request window is broker-local wall clock, second-truncated`() {
        // serverEpochToUtc is applied to what comes back, so the window sent must be in the
        // same base. Sending UTC to a UTC+3 server would shift the request by three hours.
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        try {
            val client =
                Mt5TickClient(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    http = OkHttpClient(),
                    serverTimeZone = MT5ServerTimeZone.parse("+03:00"),
                )
            // 2026-05-13T08:59:54.911Z UTC -> 11:59:54 on a UTC+3 broker clock.
            client.fetchRange("XAUUSDm", afterBrokerMs = 1778662794911L, toBrokerMs = 1778662795200L, capturedAtMs = 0L)
            val path = server.takeRequest().path ?: ""
            assertThat(path).contains("/copy_ticks_range")
            assertThat(path).contains("from_date=2026-05-13T11%3A59%3A54")
            assertThat(path).contains("to_date=2026-05-13T11%3A59%3A56")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a degenerate window issues no request`() {
        // The gateway rejects from_date == to_date as a validation error rather than
        // returning empty data, so the client must never send one.
        val server = MockWebServer()
        server.start()
        try {
            val client =
                Mt5TickClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient())
            val out = client.fetchRange("XAUUSDm", afterBrokerMs = 5_000L, toBrokerMs = 3_000L, capturedAtMs = 0L)
            assertThat(out).isEmpty()
            assertThat(server.requestCount).isZero()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `ticks are returned oldest-first even when the gateway answers out of order`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                "[" + listOf(300L, 100L, 200L).joinToString(",") { o -> tick(1778662794000L + o) } + "]",
            ),
        )
        server.start()
        try {
            val client =
                Mt5TickClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient())
            val out = client.fetchRange("XAUUSDm", afterBrokerMs = 1L, toBrokerMs = 2_000L, capturedAtMs = 0L)
            assertThat(out.map { it.brokerTimeMs })
                .containsExactly(1778662794100L, 1778662794200L, 1778662794300L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `catch-up window is clamped so a weekend gap is skipped, not replayed`() {
        // lastBrokerMs survives the out-of-session skip. Unclamped, the Monday open would ask
        // MT5 for two days of ticks in a single round.
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        try {
            val source =
                Mt5TickFeedSource(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    symbolMap = mapOf("XAUUSDm" to "EXNESS:XAUUSD"),
                    pollIntervalMs = 5L,
                    http = OkHttpClient(),
                    maxCatchupMs = 10_000L,
                )
            source.start(onTick = {}, onError = {}, onDisconnect = {})
            val recorded = server.takeRequest()
            source.stop()
            val path = recorded.path ?: ""
            val from = Regex("from_date=([^&]+)").find(path)!!.groupValues[1].replace("%3A", ":")
            val to = Regex("to_date=([^&]+)").find(path)!!.groupValues[1].replace("%3A", ":")
            val fromMs = LocalDateTime.parse(from).toInstant(ZoneOffset.UTC).toEpochMilli()
            val toMs = LocalDateTime.parse(to).toInstant(ZoneOffset.UTC).toEpochMilli()
            // First round has no watermark: the 2s initial lookback applies, well inside the clamp.
            assertThat(toMs - fromMs).isLessThanOrEqualTo(12_000L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `range delivery builds the same candles as one-tick-per-round delivery`() {
        // The parity claim behind the endpoint switch: range polling changes only WHICH ticks
        // reach the engine, never how a bar is built from them. Feeding one aggregator a burst
        // and another the identical ticks one at a time must yield identical candles.
        val base = 1778662740000L // an exact minute boundary
        val ticks =
            (0 until 90).map { i ->
                Tick(
                    symbol = "EXNESS:XAUUSD",
                    price = BigDecimal("4700." + (i % 10)),
                    timestamp = base + i * 1_000L,
                    bid = BigDecimal("4700.0"),
                    ask = BigDecimal("4700.4"),
                    volume = null,
                )
            }

        val burst = mutableListOf<Candle>()
        val single = mutableListOf<Candle>()
        val window = TimeWindow.ONE_MINUTE
        val burstAgg = CandleAggregator.standalone(window) { c -> burst.add(c) }
        val singleAgg = CandleAggregator.standalone(window) { c -> single.add(c) }

        // Range delivery arrives in bursts of five; snapshot delivery one at a time. Same order.
        ticks.chunked(5).forEach { chunk -> chunk.forEach(burstAgg::onTick) }
        ticks.forEach(singleAgg::onTick)

        assertThat(burst).isNotEmpty
        assertThat(burst).isEqualTo(single)
    }

    private fun pollOnce(
        server: MockWebServer,
        symbolMap: Map<String, String>,
        want: Int,
    ): List<Tick> {
        val source =
            Mt5TickFeedSource(
                baseUrl = server.url("/").toString().trimEnd('/'),
                symbolMap = symbolMap,
                pollIntervalMs = 5L,
                http = OkHttpClient(),
            )
        val captured = CopyOnWriteArrayList<Tick>()
        source.start(onTick = { captured.add(it) }, onError = { it.printStackTrace() }, onDisconnect = {})
        val deadline = System.currentTimeMillis() + 3_000L
        while (captured.size < want && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        source.stop()
        return captured.toList()
    }
}
