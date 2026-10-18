package com.qkt.marketdata.live.tv

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TradingView checks a client is alive with its own heartbeat frame, never a WebSocket ping. */
class TradingViewHeartbeatTest {
    @Test
    fun `the default client sends no WebSocket pings, which TradingView never answers`() {
        // A live check (2026-10-18) lost every pinging connection at 40s: "sent ping but didn't
        // receive pong within 20000ms (after 0 successful ping/pongs)".
        assertThat(TradingViewWebSocket.defaultClient().pingIntervalMillis).isZero()
    }

    @Test
    fun `a heartbeat is echoed back exactly as TradingView sent it`() {
        val received = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("~m~4~m~~h~7")
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
                ),
            )
            server.start()
            val ws = TradingViewWebSocket(url = server.url("/").toString(), origin = "http://localhost")
            ws.connect()
            try {
                val messages = (1..2).mapNotNull { received.poll(5, TimeUnit.SECONDS) }

                assertThat(messages).contains("~m~4~m~~h~7")
            } finally {
                ws.close()
            }
        }
    }
}
