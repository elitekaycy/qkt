package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitClientInitialConnectTest {
    /** Fake WebSocket that never delivers an auth ack — the auth latch will time out. */
    private fun silentWsFactory(): (Request, WebSocketListener) -> WebSocket =
        { _, _ ->
            object : WebSocket {
                override fun cancel() {}

                override fun close(
                    code: Int,
                    reason: String?,
                ): Boolean = true

                override fun queueSize(): Long = 0L

                override fun request(): Request = error("not used")

                override fun send(text: String): Boolean = true

                override fun send(bytes: ByteString): Boolean = true
            }
        }

    @Test
    fun `initial connect throws if auth never acks within timeout`() {
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = OkHttpClient(),
                clock = FixedClock(0L),
                wsFactory = silentWsFactory(),
            )

        assertThatThrownBy { client.connect() }
            .isInstanceOf(BybitConnectException::class.java)
            .hasMessageContaining("Initial Bybit connect failed")
    }
}
