package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The session API's authorization, optional fields and malformed requests. */
class BotSessionServerHttpEdgesTest {
    private val gold = "EXNESS:XAUUSD"
    private lateinit var server: BotSessionServer
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = testBridge(history = history)
        val ticks =
            (0 until 3).flatMap { bar ->
                val p = BigDecimal(2400 + bar)
                listOf(Tick(gold, p, bar * 60_000L + 1_000L), Tick(gold, p, bar * 60_000L + 30_000L))
            }
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks,
                candleWindow = testStreams().baseWindow,
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val backend = ReplayBotRunBackend(engine)
        val session = BotRunSession("edges", backend, mapOf("brain" to bridge), testStreams(), history, recorder)
        server = BotSessionServer(session, token = "secret", accountCurrency = "USD", onFinish = { null })
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun call(
        method: String,
        path: String,
        body: String = "{}",
        auth: String? = "Bearer secret",
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
        auth?.let { builder.header("Authorization", it) }
        if (method == "POST") builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a request without a bearer token is unauthorized and told the scheme`() {
        val none = call("GET", "/status", auth = null)
        val basic = call("GET", "/status", auth = "Basic c2VjcmV0")

        assertThat(none.statusCode()).isEqualTo(401)
        assertThat(none.headers().firstValue("WWW-Authenticate")).hasValue("Bearer")
        assertThat(basic.statusCode()).isEqualTo(401)
        assertThat(basic.body()).isEqualTo("""{"ok":false,"error":"unauthorized"}""")
    }

    @Test
    fun `the bearer scheme name is case blind`() {
        assertThat(call("GET", "/status", auth = "bearer secret").statusCode()).isEqualTo(200)
    }

    @Test
    fun `next takes an explicit timeframe and refuses a body without a symbol`() {
        val bar = call("POST", "/next", """{"symbol":"$gold","tf":"1m"}""")
        val missing = call("POST", "/next", """{"tf":"1m"}""")

        assertThat(bar.body()).contains("\"type\":\"bar\"").contains("\"timeMs\":0")
        assertThat(missing.statusCode()).isEqualTo(400)
        assertThat(missing.body()).isEqualTo("""{"ok":false,"error":"missing field 'symbol'"}""")
    }

    @Test
    fun `bars fall back to a hundred when the count is not a number and take a timeframe`() {
        call("POST", "/next", """{"symbol":"$gold"}""")
        call("POST", "/next", """{"symbol":"$gold"}""")

        val bars = call("GET", "/bars?symbol=$gold&count=many&tf=1m").body()
        val one = call("GET", "/bars?symbol=$gold&count=1").body()

        assertThat(Regex("\"type\":\"bar\"").findAll(bars).count()).isEqualTo(2)
        assertThat(Regex("\"type\":\"bar\"").findAll(one).count()).isEqualTo(1)
        assertThat(one).contains("\"timeMs\":60000")
    }

    @Test
    fun `finish refuses a flatten flag that is not a value`() {
        val reply = call("POST", "/finish", """{"flatten":{"now":true}}""")

        assertThat(reply.statusCode()).isEqualTo(400)
        assertThat(server.finished).isFalse()
    }

    @Test
    fun `finish with flatten closes the book first and a second finish is a no-op`() {
        val first = call("POST", "/finish", """{"flatten":true}""")
        val second = call("POST", "/finish")

        assertThat(first.body()).contains("\"finished\":true").contains("\"openLegs\":[]")
        assertThat(second.body()).isEqualTo("""{"ok":true,"finished":true}""")
        assertThat(server.finished).isTrue()
    }
}
