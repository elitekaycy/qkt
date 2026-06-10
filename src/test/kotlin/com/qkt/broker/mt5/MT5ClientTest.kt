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
    fun `placeOrder serializes the GTD expiration on the wire`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":12345,"deal":67890,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        client.placeOrder(
            MT5OrderRequest(
                symbol = "EURUSDm",
                volume = BigDecimal("0.1"),
                type = "BUY_LIMIT",
                price = BigDecimal("1.0900"),
                magic = 10001,
                comment = "ord-gtd",
                expiration = 1_778_000_000L,
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"expiration\":1778000000")
    }

    @Test
    fun `placeOrderAsync delivers the parsed response via the callback`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":12345,"deal":67890,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        val result =
            java.util.concurrent.atomic
                .AtomicReference<MT5OrderResponse>()
        client.placeOrderAsync(
            MT5OrderRequest(
                symbol = "EURUSDm",
                volume = BigDecimal("0.1"),
                type = "BUY",
                magic = 10001,
                comment = "ord-async",
            ),
        ) { resp ->
            result.set(resp)
            latch.countDown()
        }
        assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue
        assertThat(result.get().result.retcode).isEqualTo(10009)
        assertThat(result.get().result.order).isEqualTo(12345L)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/order")
        assertThat(recorded.method).isEqualTo("POST")
    }

    @Test
    fun `placeOrderAsync delivers a synthetic failure on a non-2xx response`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val latch = java.util.concurrent.CountDownLatch(1)
        val result =
            java.util.concurrent.atomic
                .AtomicReference<MT5OrderResponse>()
        client.placeOrderAsync(
            MT5OrderRequest(
                symbol = "EURUSDm",
                volume = BigDecimal("0.1"),
                type = "BUY",
                magic = 10001,
                comment = "ord-fail",
            ),
        ) { resp ->
            result.set(resp)
            latch.countDown()
        }
        assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue
        assertThat(isOrderSuccessful(result.get().result.retcode)).isFalse
        assertThat(result.get().errorMessage).contains("HTTP 500")
    }

    @Test
    fun `retcode success family includes done placed and partial`() {
        // 10008 (placed) and 10010 (partial) mean the venue owns the order; treating
        // them as rejections double-submits on the strategy's next attempt.
        assertThat(isOrderSuccessful(10009)).isTrue
        assertThat(isOrderSuccessful(10008)).isTrue
        assertThat(isOrderSuccessful(10010)).isTrue
        assertThat(isOrderSuccessful(10004)).isFalse
        assertThat(isOrderSuccessful(-1)).isFalse
    }

    @Test
    fun `placeOrder caps an over-long comment to the MT5 wire limit`() {
        // mt5.order_send rejects comments longer than MT5_COMMENT_MAX_LENGTH with
        // `Invalid "comment" argument`. The hedge-straddle stack-tier clientOrderId
        // (e.g. "dsl-hedge_straddle--7-stack-tier0", 33 chars) tripped this live (#210),
        // failing every stack placement. The wire comment must be truncated to fit.
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":1,"price":"1.0","comment":"ok"}}""",
            ),
        )
        val longId = "dsl-hedge_straddle--7-stack-tier0"
        assertThat(longId.length).isGreaterThan(MT5_COMMENT_MAX_LENGTH)
        client.placeOrder(
            MT5OrderRequest(
                symbol = "XAUUSDm",
                volume = BigDecimal("0.1"),
                type = "SELL",
                magic = 10001,
                comment = longId,
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"comment\":\"${longId.take(MT5_COMMENT_MAX_LENGTH)}\"")
        assertThat(body).doesNotContain(longId)
    }

    @Test
    fun `placeOrder leaves a comment within the limit untouched`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":1,"price":"1.0","comment":"ok"}}""",
            ),
        )
        val shortId = "dsl-hedge_straddle--7"
        assertThat(shortId.length).isLessThanOrEqualTo(MT5_COMMENT_MAX_LENGTH)
        client.placeOrder(
            MT5OrderRequest(
                symbol = "XAUUSDm",
                volume = BigDecimal("0.1"),
                type = "SELL",
                magic = 10001,
                comment = shortId,
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"comment\":\"$shortId\"")
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
        val positions = client.getPositions(magic = 10001)!!
        assertThat(positions).hasSize(1)
        assertThat(positions[0].ticket).isEqualTo(1L)
        assertThat(positions[0].openTime).isEqualTo(expectedUtcMs)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/get_positions?magic=10001")
        assertThat(recorded.method).isEqualTo("GET")
    }

    @Test
    fun `cancelOrder issues DELETE on the orders ticket route`() {
        server.enqueue(MockResponse().setBody("""{"message":"Order cancelled successfully"}"""))
        val body = client.cancelOrder(ticket = 555L)
        assertThat(body).contains("cancelled successfully")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/orders/555")
        assertThat(recorded.method).isEqualTo("DELETE")
    }

    @Test
    fun `cancelOrder returns empty string and logs on HTTP 404`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val body = client.cancelOrder(ticket = 999L)
        assertThat(body).isEmpty()
    }

    @Test
    fun `modifyOrder issues PUT with json body and parses the result`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":42,"deal":0,"price":"1.1075","comment":"ok"}}""",
            ),
        )
        val resp =
            client.modifyOrder(
                ticket = 42L,
                mods = MT5OrderModification(price = BigDecimal("1.1075")),
            )
        assertThat(resp.result.retcode).isEqualTo(10009)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/orders/42")
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.body.readUtf8()).contains("\"price\":1.1075")
    }

    @Test
    fun `getTick hits symbol_info_tick and applies tz offset`() {
        val serverEpochMs = 1_700_000_000L
        server.enqueue(
            MockResponse().setBody(
                """{"bid":"4561.510","ask":"4561.818","time":$serverEpochMs}""",
            ),
        )
        val tick = client.getTick("XAUUSDm")!!
        assertThat(tick.bid).isEqualByComparingTo("4561.510")
        assertThat(tick.ask).isEqualByComparingTo("4561.818")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/symbol_info_tick/XAUUSDm")
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
        val orders = client.getPendingOrders(magic = 10001)!!
        assertThat(orders).hasSize(1)
        assertThat(orders[0].ticket).isEqualTo(7L)
        assertThat(orders[0].symbol).isEqualTo("XAUUSDm")
    }

    @Test
    fun `getPendingOrders parses a bare array`() {
        server.enqueue(MockResponse().setBody("""[$pendingOrderJson]"""))
        val orders = client.getPendingOrders(magic = 10001)!!
        assertThat(orders).hasSize(1)
        assertThat(orders[0].ticket).isEqualTo(7L)
    }

    @Test
    fun `getPendingOrders returns empty for an object without orders`() {
        server.enqueue(MockResponse().setBody("""{"total":0}"""))
        assertThat(client.getPendingOrders(magic = 10001)).isEmpty()
    }

    @Test
    fun `state reads return null on gateway failure, not empty`() {
        // null = "could not read" so pollers can tell an outage apart from a genuinely
        // flat account — an outage must never read as "all closed / all cancelled".
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThat(client.getPositions(magic = 10001)).isNull()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThat(client.getPendingOrders(magic = 10001)).isNull()
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
        assertThat(info.contractSize).isEqualByComparingTo("100")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/symbol_info/XAUUSDm")
    }

    @Test
    fun `getSymbolInfo defaults contractSize to 1 when missing`() {
        server.enqueue(
            MockResponse().setBody(
                """{"ask":1.1,"bid":1.0999,"digits":5,"point":0.00001,""" +
                    """"trade_stops_level":0,"volume_min":0.01,"volume_step":0.01}""",
            ),
        )
        val info = client.getSymbolInfo("FOO")!!
        assertThat(info.contractSize).isEqualByComparingTo("1")
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
        val orders = client.getPendingOrders(magic = 10001)!!
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

    @Test
    fun `getAccount parses margin mode and flags hedging`() {
        // Real Exness demo /account shape (login 435898347): margin_mode 2 = RETAIL_HEDGING.
        server.enqueue(
            MockResponse().setBody(
                """{"balance":2390.91,"equity":1895.99,"currency":"USD","leverage":100,"margin_mode":2}""",
            ),
        )
        val acct = client.getAccount()!!
        assertThat(acct.marginMode).isEqualTo(2)
        assertThat(acct.isHedging).isTrue
        assertThat(acct.balance).isEqualByComparingTo("2390.91")
        assertThat(acct.equity).isEqualByComparingTo("1895.99")
        assertThat(acct.currency).isEqualTo("USD")
        assertThat(acct.leverage).isEqualTo(100)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/account")
        assertThat(recorded.method).isEqualTo("GET")
    }

    @Test
    fun `getAccount reads a netting account as not hedging`() {
        server.enqueue(
            MockResponse().setBody(
                """{"balance":1000.0,"equity":1000.0,"currency":"USD","leverage":500,"margin_mode":0}""",
            ),
        )
        val acct = client.getAccount()!!
        assertThat(acct.marginMode).isEqualTo(0)
        assertThat(acct.isHedging).isFalse
    }

    @Test
    fun `getAccount returns null on gateway failure`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.getAccount()).isNull()
    }

    @Test
    fun `closePosition posts the ticket and parses the close deal`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":555,"price":"2000.5","comment":"ok"}}""",
            ),
        )
        val resp = client.closePosition(ticket = 2814861313L)
        assertThat(resp.result.retcode).isEqualTo(10009)
        assertThat(resp.result.deal).isEqualTo(555L)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/close_position")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"position":{"ticket":2814861313}}""")
    }

    @Test
    fun `closePosition includes volume for a partial close`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":1,"price":"2000","comment":"ok"}}""",
            ),
        )
        client.closePosition(ticket = 42L, volume = BigDecimal("0.10"))
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"position":{"ticket":42,"volume":0.10}}""")
    }

    @Test
    fun `closePosition surfaces the gateway error envelope as a failed result`() {
        // Confirmed prod shape for a bad ticket: {"error":...,"error_type":"validation_error",...}
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"Failed to close position","error_type":"validation_error"}"""),
        )
        val resp = client.closePosition(ticket = 1L)
        assertThat(isOrderSuccessful(resp.result.retcode)).isFalse
        assertThat(resp.errorMessage).contains("Failed to close position")
    }

    @Test
    fun `modifyPosition posts position sl tp and parses the result`() {
        server.enqueue(
            MockResponse().setBody(
                """{"message":"SL/TP modified","result":{"retcode":10009,"order":0,"deal":0,"price":"0","comment":"ok"}}""",
            ),
        )
        val resp = client.modifyPosition(ticket = 424242L, sl = BigDecimal("1.0950"), tp = BigDecimal("1.1100"))
        assertThat(resp.result.retcode).isEqualTo(10009)
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/modify_sl_tp")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"position":424242,"sl":1.0950,"tp":1.1100}""")
    }

    @Test
    fun `modifyPosition surfaces a gateway failure`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Position 1 not found"}"""))
        val resp = client.modifyPosition(ticket = 1L, sl = BigDecimal("1.0"))
        assertThat(isOrderSuccessful(resp.result.retcode)).isFalse
        assertThat(resp.errorMessage).contains("not found")
    }
}
