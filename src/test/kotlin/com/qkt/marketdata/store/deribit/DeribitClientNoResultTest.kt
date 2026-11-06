package com.qkt.marketdata.store.deribit

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitClientNoResultTest {
    @Test
    fun `an answer with neither result nor error fails at once naming the method`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0"}"""))
            server.start()
            val base = server.url("/api/v2").toString()

            assertThatThrownBy { DeribitClient(base, base, sleep = {}).deliveryPrices("btc_usdc", 0) }
                .isInstanceOf(IOException::class.java)
                .hasMessageContaining("returned no result")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
}
