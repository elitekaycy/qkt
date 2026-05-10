package com.qkt.broker.mt5

import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5ClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client =
            MT5Client(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                tzOffsetHours = 2,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `placeOrder sends correct json and parses response`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":12345,"deal":67890,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val resp =
            client.placeOrder(
                MT5OrderRequest(
                    symbol = "EURUSDm",
                    volume = BigDecimal("0.1"),
                    type = "BUY",
                    magic = 10001,
                    comment = "ord-1",
                ),
            )
        assertThat(resp.result.retcode).isEqualTo(10009)
        assertThat(resp.result.deal).isEqualTo(67890L)
        assertThat(resp.result.price).isEqualByComparingTo("1.1234")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/order")
        assertThat(recorded.method).isEqualTo("POST")
    }

    @Test
    fun `getPositions parses list and applies tz offset`() {
        val serverEpochMs = 1_700_000_000_000L
        val expectedUtcMs = serverEpochMs - 2L * 3600L * 1000L
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":1,"symbol":"EURUSDm","type":0,"volume":"0.1","price_open":"1.1","sl":"0","tp":"0","profit":"0","magic":10001,"open_time":$serverEpochMs,"comment":"x"}]""",
            ),
        )
        val positions = client.getPositions(magic = 10001)
        assertThat(positions).hasSize(1)
        assertThat(positions[0].ticket).isEqualTo(1L)
        assertThat(positions[0].openTime).isEqualTo(expectedUtcMs)
    }

    @Test
    fun `isReady returns true on 200`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        assertThat(client.isReady()).isTrue
    }

    @Test
    fun `isReady returns false on 5xx`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.isReady()).isFalse
    }
}
