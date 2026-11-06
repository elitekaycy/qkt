package com.qkt.marketdata.live.tv

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Connecting through the companion factories, against a local WebSocket standing in for TradingView. */
class TradingViewConnectTest {
    private val closes = LinkedBlockingQueue<Int>()

    /** Answers the client's `go` command with an unparseable frame and then a binary `probe` frame. */
    private val server =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                if (!text.contains("\"go\"")) return
                webSocket.send(TradingViewFraming.encode("not json"))
                webSocket.send(TradingViewFraming.encode("""{"m":"probe","p":[1]}""").encodeUtf8())
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                closes.add(code)
                webSocket.close(code, null)
            }
        }

    @Test
    fun `a binary frame is read as text and an unparseable frame is skipped`() {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().withWebSocketUpgrade(server))
            mock.start()
            val ws = TradingViewWebSocket.connect(url = mock.url("/").toString(), origin = "http://probe")
            val frames = LinkedBlockingQueue<TradingViewFrame>()
            val removed = LinkedBlockingQueue<TradingViewFrame>()
            val keep = listener { frames.add(it) }
            val drop = listener { removed.add(it) }
            ws.addListener(keep)
            ws.addListener(drop)
            ws.removeListener(drop)
            try {
                assertThat(mock.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Origin")).isEqualTo("http://probe")
                ws.send("go", emptyList())

                val frame = frames.poll(5, TimeUnit.SECONDS) as TradingViewFrame.Message

                assertThat(frame.method).isEqualTo("probe")
                assertThat(frames).isEmpty()
                assertThat(removed).isEmpty()
            } finally {
                ws.close()
            }
        }
    }

    @Test
    fun `a market source connects with the given origin and closing it closes the socket`() {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().withWebSocketUpgrade(server))
            mock.start()
            val source = TradingViewMarketSource.connect(url = mock.url("/").toString(), origin = "http://src")

            assertThat(mock.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Origin")).isEqualTo("http://src")
            source.close()

            assertThat(closes.poll(5, TimeUnit.SECONDS)).isEqualTo(1000)
        }
    }

    private fun listener(handle: (TradingViewFrame) -> Unit) =
        object : TradingViewListener {
            override fun onFrame(frame: TradingViewFrame) = handle(frame)

            override fun onConnected() {}

            override fun onDisconnected(reason: String) {}
        }
}
