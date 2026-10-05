package com.qkt.connector.gateway

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A slow answer (a page of marks costing the venue many calls) is waited for as long as the timeout allows. */
class GatewayClientTimeoutTest {
    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `an answer slower than ten seconds arrives within a longer timeout`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"marks":[{"time":1,"mark":"2"}]}""")
                .setHeadersDelay(10_500, TimeUnit.MILLISECONDS),
        )
        val client =
            GatewayClient(server.url("/").toString().trimEnd('/'), "k", httpTimeoutMs = 20_000, retryAttempts = 1)

        val marks = client.marks("BTC_USDC-PERPETUAL", 60_000L, 0L, 60_000L)

        assertThat(marks.single().mark).isEqualTo("2")
    }
}
