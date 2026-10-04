package com.qkt.connector.gateway

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayStreamTest {
    private val server = MockWebServer().apply { start() }
    private val events = CopyOnWriteArrayList<Long>()
    private val resets = CopyOnWriteArrayList<String>()
    private val connections = CopyOnWriteArrayList<Boolean>()
    private var stream: GatewayStream? = null

    @AfterEach
    fun stop() {
        stream?.stop()
        server.shutdown()
    }

    private fun event(
        seq: Long,
        id: String = "s1",
        type: String = "order",
    ) = """{"stream":"$id","seq":$seq,"type":"$type","time":1,"data":{}}"""

    /** A socket that sends [messages] on open, then closes when [close] is true. */
    private fun socket(
        vararg messages: String,
        close: Boolean = false,
    ) = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                messages.forEach { webSocket.send(it) }
                if (close) webSocket.close(1001, "going away")
            }
        },
    )

    private fun start(expected: Int): CountDownLatch {
        val latch = CountDownLatch(expected)
        stream =
            GatewayStream(
                server.url("/").toString().trimEnd('/'),
                "secret",
                onEvent = {
                    events += it.seq
                    latch.countDown()
                },
                onReset = {
                    resets += it
                    latch.countDown()
                },
                onConnection = { connected, _ -> connections += connected },
                initialBackoffMs = 20,
            ).also { it.start() }
        return latch
    }

    @Test
    fun `events arrive in order with the bearer token, and a repeated seq is dropped`() {
        server.enqueue(socket(event(1), event(2), event(2), event(3)))

        assertThat(start(3).await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(events).containsExactly(1L, 2L, 3L)
        val upgrade = server.takeRequest()
        assertThat(upgrade.path).isEqualTo("/v1/stream")
        assertThat(upgrade.getHeader("Authorization")).isEqualTo("Bearer secret")
    }

    @Test
    fun `a dropped socket reconnects from the last seq it processed, naming its stream`() {
        server.enqueue(socket(event(1), event(2), close = true))
        server.enqueue(socket(event(3)))

        assertThat(start(3).await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(events).containsExactly(1L, 2L, 3L)
        assertThat(server.takeRequest().path).isEqualTo("/v1/stream")
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)?.path).isEqualTo("/v1/stream?since=2&stream=s1")
        assertThat(connections).startsWith(true, false, true)
    }

    @Test
    fun `a reset, a new stream or a skipped seq asks for a resync before going on`() {
        server.enqueue(
            socket(
                event(1),
                """{"stream":"s1","seq":9,"type":"reset"}""",
                event(10),
                event(1, id = "s2"),
                event(3, id = "s2"),
            ),
        )

        assertThat(start(7).await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(resets).containsExactly("reset", "stream s1 -> s2", "seq 1 -> 3")
        assertThat(events).containsExactly(1L, 10L, 1L, 3L)
    }
}
