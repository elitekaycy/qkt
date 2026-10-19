package com.qkt.marketdata.live.tv

import com.qkt.common.FixedClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A lost TradingView connection is opened again and the quote session re-created on it. */
class TradingViewReconnectTest {
    /** A server-side connection that heartbeats once and records what the client sends. */
    private fun connection(received: LinkedBlockingQueue<String>) =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    webSocket.send("~m~4~m~~h~1")
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    received.add(text)
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    webSocket.close(code, null)
                }
            },
        )

    private fun MockWebServer.socket(staleAfterMs: Long = 30_000L) =
        TradingViewWebSocket(
            url = url("/").toString(),
            origin = "http://localhost",
            reconnectDelayMs = { 20L },
            staleAfterMs = staleAfterMs,
        )

    @Test
    fun `a dropped connection reconnects and re-creates the quote session on the new one`() {
        val first = LinkedBlockingQueue<String>()
        val second = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(connection(first))
            server.enqueue(connection(second))
            server.start()
            val ws = server.socket()
            val session = TradingViewQuoteSession(ws, FixedClock(0L), sessionIdGenerator = { "qs_test" })
            val disconnects = AtomicInteger()
            val reconnected = CountDownLatch(1)
            ws.connect()
            assertThat(first.poll(5, TimeUnit.SECONDS)).contains("set_auth_token")
            session.subscribe(
                listOf("BINANCE:BTCUSDT"),
                {},
                {},
                { disconnects.incrementAndGet() },
                { reconnected.countDown() },
            )

            // Drop the client's own socket mid-session, as a network failure would.
            dropClientSocket(ws)

            try {
                val resent = generateSequence { second.poll(5, TimeUnit.SECONDS) }.take(5).toList()
                assertThat(resent.joinToString()).contains(
                    "set_auth_token",
                    "quote_create_session",
                    "quote_set_fields",
                    """"quote_add_symbols","p":["qs_test","BINANCE:BTCUSDT"]""",
                )
                assertThat(reconnected.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(disconnects.get()).isEqualTo(1)
            } finally {
                ws.close()
            }
        }
    }

    @Test
    fun `a connection that has gone silent is dropped and opened again`() {
        val first = LinkedBlockingQueue<String>()
        val second = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            // The first connection answers the upgrade and then says nothing at all.
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            first.add(text)
                        }
                    },
                ),
            )
            server.enqueue(connection(second))
            server.start()
            val ws = server.socket(staleAfterMs = 300L)
            ws.connect()
            try {
                assertThat(second.poll(5, TimeUnit.SECONDS)).contains("set_auth_token")
            } finally {
                ws.close()
            }
        }
    }

    @Test
    fun `closing the client does not reconnect`() {
        val first = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(connection(first))
            server.enqueue(connection(LinkedBlockingQueue()))
            server.start()
            val ws = server.socket()
            ws.connect()
            assertThat(first.poll(5, TimeUnit.SECONDS)).contains("set_auth_token")

            ws.close()
            Thread.sleep(300)

            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `reconnect attempts back off from one second to a thirty second cap`() {
        assertThat((1..7).map { TradingViewConnection.backoffMs(it) })
            .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
    }

    private fun dropClientSocket(ws: TradingViewWebSocket) {
        val field = TradingViewWebSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(ws) as java.util.concurrent.atomic.AtomicReference<WebSocket?>).get()?.cancel()
    }
}
