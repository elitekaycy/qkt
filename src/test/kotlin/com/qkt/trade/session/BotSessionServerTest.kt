package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BotSessionServerTest {
    private var server: BotSessionServer? = null
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun start(): BotSessionServer {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = BotBridgeStrategy()
        val ticks =
            (0 until 4).flatMap { bar ->
                val base = bar * 60_000L
                listOf(
                    Tick("EXNESS:XAUUSD", BigDecimal(2400 + bar), base + 1_000L),
                    Tick("EXNESS:XAUUSD", BigDecimal(2401 + bar), base + 30_000L),
                )
            } + Tick("EXNESS:XAUUSD", BigDecimal("2410"), 240_000L + 1_000L)
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks,
                candleWindow = TimeWindow.parse("1m"),
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val session =
            BotRunSession(
                runId = "srv-test",
                backend = ReplayBotRunBackend(engine),
                bridges = mapOf("brain" to bridge),
                history = history,
                recorder = recorder,
            )
        val s =
            BotSessionServer(
                session = session,
                token = "secret",
                accountCurrency = "USD",
                onFinish = { null },
            )
        s.start()
        server = s
        return s
    }

    private fun call(
        server: BotSessionServer,
        method: String,
        path: String,
        body: String? = null,
        token: String = "secret",
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
                .header("Authorization", "Bearer $token")
        if (method == "POST") {
            builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `status next intent and finish round-trip over http`() {
        val s = start()
        assertThat(call(s, "GET", "/status").body()).contains("\"run\":\"srv-test\"")
        val bar = call(s, "POST", "/next", """{"symbol":"EXNESS:XAUUSD"}""")
        assertThat(bar.body()).contains("\"type\":\"bar\"").contains("\"timeMs\":0")
        val source =
            com.qkt.trade
                .renderBotStrategy(
                    com.qkt.trade.BotIntent(
                        side = com.qkt.common.Side.BUY,
                        qktSymbol = "EXNESS:XAUUSD",
                        lots = BigDecimal.ONE,
                        sizingDsl = null,
                        limitPrice = null,
                        stopPrice = null,
                        stopLimitPrice = null,
                        sl = null,
                        tp = null,
                        tif = com.qkt.trade.BotTif.GTC,
                        expiresAtMs = null,
                    ),
                ).replace("\n", "\\n")
        val intent =
            call(
                s,
                "POST",
                "/intent",
                """{"identity":"brain","source":"$source"}""",
            )
        assertThat(intent.statusCode()).isEqualTo(200)
        assertThat(intent.body()).contains("\"queued\":true")
        call(s, "POST", "/next", """{"symbol":"EXNESS:XAUUSD"}""")
        call(s, "POST", "/next", """{"symbol":"EXNESS:XAUUSD"}""")
        val fin = call(s, "POST", "/finish")
        assertThat(fin.body()).contains("\"finished\":true")
        assertThat(fin.body()).contains("\"trades\":1")
    }

    @Test
    fun `wrong bearer token is unauthorized`() {
        val s = start()
        assertThat(call(s, "GET", "/status", token = "nope").statusCode()).isEqualTo(401)
    }
}
