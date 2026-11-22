package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.marketdata.Tick
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `qkt bot session start` closes the server the moment [BotSessionServer.finished] turns true. The
 * finish reply must already be on the wire by then: a session that reports itself finished while its
 * reply is still being written loses that reply, and `qkt bot session finish` fails with
 * "unexpected end of stream".
 */
class BotSessionServerFinishReplyTest {
    private val gold = "EXNESS:XAUUSD"

    private fun server(): BotSessionServer {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = testBridge(history = history)
        val ticks = listOf(Tick(gold, BigDecimal(2400), 1_000L), Tick(gold, BigDecimal(2401), 30_000L))
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks,
                candleWindow = testStreams().baseWindow,
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val backend = ReplayBotRunBackend(engine)
        val session = BotRunSession("finish", backend, mapOf("brain" to bridge), testStreams(), history, recorder)
        return BotSessionServer(session, token = "secret", accountCurrency = "USD", onFinish = { "/report" })
    }

    @Test
    fun `a session closed as soon as it reports finished has already delivered the finish reply`() {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        repeat(ROUNDS) { round ->
            val server = server().apply { start() }
            // What serveSession does, without its poll interval: close the server the instant it is finished.
            val closer =
                Thread {
                    while (!server.finished) Thread.onSpinWait()
                    server.close()
                }.apply {
                    isDaemon = true
                    start()
                }
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/finish"))
                    .header("Authorization", "Bearer secret")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build()

            val reply =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (e: IOException) {
                    throw AssertionError("round $round lost the finish reply: $e", e)
                }

            assertThat(reply.statusCode()).isEqualTo(200)
            assertThat(reply.body()).contains("\"finished\":true").contains("\"reportDir\":\"/report\"").endsWith("}")
            closer.join(5_000L)
            server.close()
        }
    }

    @Test
    fun `a finish whose reply cannot be written still ends the session`() {
        val server = server().apply { start() }
        try {
            java.net.Socket("127.0.0.1", server.boundPort).use { socket ->
                val body = "{}"
                socket.getOutputStream().write(
                    (
                        "POST /finish HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer secret\r\n" +
                            "Content-Length: ${body.length}\r\n\r\n$body"
                    ).toByteArray(),
                )
                socket.getOutputStream().flush()
            }
            val deadline = System.currentTimeMillis() + 5_000L
            while (!server.finished && System.currentTimeMillis() < deadline) Thread.onSpinWait()

            assertThat(server.finished).isTrue()
        } finally {
            server.close()
        }
    }

    private companion object {
        const val ROUNDS = 60
    }
}
