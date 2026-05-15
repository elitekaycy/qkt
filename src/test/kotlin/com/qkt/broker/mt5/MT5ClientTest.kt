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

    private val pendingOrderJson =
        """{"ticket":7,"symbol":"XAUUSDm","type":"BUY_STOP","volume":"0.1","price_open":"4700.0","sl":"4682.0","tp":"4712.0","magic":10001,"time_setup":1700000000,"time_expiration":1700000600,"comment":"x"}"""

    @Test
    fun `getPendingOrders parses a wrapped orders object`() {
        server.enqueue(MockResponse().setBody("""{"orders":[$pendingOrderJson],"total":1}"""))
        val orders = client.getPendingOrders(magic = 10001)
        assertThat(orders).hasSize(1)
        assertThat(orders[0].ticket).isEqualTo(7L)
        assertThat(orders[0].symbol).isEqualTo("XAUUSDm")
    }

    @Test
    fun `getPendingOrders parses a bare array`() {
        server.enqueue(MockResponse().setBody("""[$pendingOrderJson]"""))
        val orders = client.getPendingOrders(magic = 10001)
        assertThat(orders).hasSize(1)
        assertThat(orders[0].ticket).isEqualTo(7L)
    }

    @Test
    fun `getPendingOrders returns empty for an object without orders`() {
        server.enqueue(MockResponse().setBody("""{"total":0}"""))
        assertThat(client.getPendingOrders(magic = 10001)).isEmpty()
    }

    @Test
    fun `getSymbolInfo parses volume rules and basic price metadata`() {
        server.enqueue(
            MockResponse().setBody(
                """{"ask":4561.818,"bid":4561.51,"digits":3,"point":0.001,""" +
                    """"trade_stops_level":0,"volume_min":0.01,"volume_step":0.01,""" +
                    """"trade_contract_size":100.0}""",
            ),
        )
        val info = client.getSymbolInfo("XAUUSDm")!!
        assertThat(info.volumeStep).isEqualByComparingTo("0.01")
        assertThat(info.volumeMin).isEqualByComparingTo("0.01")
        assertThat(info.point).isEqualByComparingTo("0.001")
        assertThat(info.digits).isEqualTo(3)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/symbol_info/XAUUSDm")
    }

    @Test
    fun `getPendingOrders survives a transient row missing ticket and price_open`() {
        // Gateway has been observed emitting partially-populated rows mid-placement;
        // before defensive parsing, every poll during a rejection cycle killed the poller.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":7,"symbol":"XAUUSDm","type":"BUY_STOP","volume":"0.1",""" +
                    """"price_open":"4700.0","sl":"0","tp":"0","magic":10001,""" +
                    """"time_setup":1700000000,"time_expiration":0,"comment":"good"},""" +
                    """{"symbol":"XAUUSDm","type":"BUY_STOP","magic":10001}]""",
            ),
        )
        val orders = client.getPendingOrders(magic = 10001)
        assertThat(orders).hasSize(2)
        assertThat(orders[0].ticket).isEqualTo(7L)
        assertThat(orders[1].ticket).isEqualTo(0L)
        assertThat(orders[1].priceOpen).isEqualByComparingTo("0")
    }

    @Test
    fun `getSymbolInfo returns null when gateway returns 404`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        assertThat(client.getSymbolInfo("UNKNOWN")).isNull()
    }
}
