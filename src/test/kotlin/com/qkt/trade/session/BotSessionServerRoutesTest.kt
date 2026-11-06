package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BotSessionServerRoutesTest {
    private val gold = "EXNESS:XAUUSD"
    private var server: BotSessionServer? = null
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    /** Three 1m bars of gold, a tick at +1 s and +30 s of each, then one closing tick. */
    private fun session(): BotRunSession {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = testBridge(history = history)
        val ticks =
            (0 until 3).flatMap { bar ->
                val base = bar * 60_000L
                listOf(
                    Tick(gold, BigDecimal(2400 + bar), base + 1_000L),
                    Tick(gold, BigDecimal(2401 + bar), base + 30_000L),
                )
            } + Tick(gold, BigDecimal("2410"), 181_000L)
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks,
                candleWindow = testStreams().baseWindow,
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val backend = ReplayBotRunBackend(engine)
        return BotRunSession("routes", backend, mapOf("brain" to bridge), testStreams(), history, recorder)
    }

    private fun start(): BotSessionServer {
        val s = BotSessionServer(session(), token = "secret", accountCurrency = "USD", onFinish = { null })
        s.start()
        server = s
        return s
    }

    private fun call(
        method: String,
        path: String,
        body: String = "{}",
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server?.boundPort}$path"))
                .header("Authorization", "Bearer secret")
        if (method == "POST") builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `an unknown route is not found`() {
        start()

        val reply = call("GET", "/nope")

        assertThat(reply.statusCode()).isEqualTo(404)
        assertThat(reply.body()).isEqualTo("""{"ok":false,"error":"not found"}""")
    }

    @Test
    fun `a quote before any bar is a client error naming the fix`() {
        start()

        val reply = call("GET", "/quote?symbol=$gold")

        assertThat(reply.statusCode()).isEqualTo(400)
        assertThat(reply.body()).contains("no quote yet for $gold").contains("call /next first")
    }

    @Test
    fun `a missing query parameter is a client error`() {
        start()

        val reply = call("GET", "/quote")

        assertThat(reply.statusCode()).isEqualTo(400)
        assertThat(reply.body()).isEqualTo("""{"ok":false,"error":"missing query param 'symbol'"}""")
    }

    @Test
    fun `after a bar the quote is the last tick's price on both sides when the feed has no book`() {
        start()
        call("POST", "/next", """{"symbol":"$gold"}""")

        val reply = call("GET", "/quote?symbol=$gold")

        assertThat(reply.statusCode()).isEqualTo(200)
        assertThat(reply.body()).isEqualTo("""{"ok":true,"symbol":"$gold","bid":2401,"ask":2401,"timeMs":61000}""")
    }

    @Test
    fun `bars without a timeframe read the session's base series`() {
        val handlers =
            BotSessionHandlers(
                session = session(),
                accountCurrency = "USD",
                onFinish = { null },
                quoteContextFor = null,
                readsJournal = null,
                trail = null,
                clock = FixedClock(0L),
            )
        handlers.next(gold)
        handlers.next(gold)

        val bars = handlers.bars(gold, 5)

        assertThat(bars).startsWith("[{\"ok\":true,\"type\":\"bar\",\"symbol\":\"$gold\",\"timeMs\":0,")
        assertThat(bars).contains("\"timeMs\":60000,")
    }
}
