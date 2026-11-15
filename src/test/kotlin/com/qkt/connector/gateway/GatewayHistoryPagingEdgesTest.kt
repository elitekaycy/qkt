package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.marketdata.flow.FlowKind
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Depth, open-interest and print history: a page that does not advance, an empty range, an unlisted symbol. */
class GatewayHistoryPagingEdgesTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val bodies = ConcurrentHashMap<String, String>()
    private val paths = CopyOnWriteArrayList<String>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = requireNotNull(request.requestUrl).encodedPath
                        paths += path
                        return bodies[path]?.let { FakeWire.ok(it) } ?: MockResponse().setResponseCode(404)
                    }
                }
            start()
        }
    private val client = GatewayClient(server.url("/").toString().trimEnd('/'), "secret", retryAttempts = 1)
    private val symbols = GatewaySymbols("DERIBIT:")
    private val listing =
        """[{"code":"$code","kind":"perpetual","currency":"USDC","contract_size":"1","tick_size":"0.1",""" +
            """"volume_step":"0.0001","volume_min":"0.0001"}]"""

    @AfterEach
    fun stop() = server.shutdown()

    private fun declare(vararg capabilities: String) {
        bodies["/v1/health"] = FakeWire.health("7", false, 0, capabilities.toList())
        bodies["/v1/instruments"] = listing
    }

    @Test
    fun `a depth page whose next does not advance is refused`() {
        bodies["/v1/depth"] = """{"depth":[],"next":100}"""

        assertThatThrownBy { client.depth(code, 100L, 200L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("gateway depth of $code does not advance past 100")
        assertThat(client.depth(code, 201L, 200L)).isEmpty()
        assertThat(paths).containsExactly("/v1/depth")
    }

    @Test
    fun `an open-interest page whose next does not advance is refused`() {
        bodies["/v1/open-interest"] = """{"open_interest":[{"time":90,"open_interest":"5"}],"next":90}"""

        assertThatThrownBy { client.openInterest(code, 100L, 200L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("gateway open interest of $code does not advance past 100")
        assertThat(client.openInterest(code, 201L, 200L)).isEmpty()
        assertThat(paths).containsExactly("/v1/open-interest")
    }

    @Test
    fun `a print page whose next does not advance is refused and an empty range asks nothing`() {
        bodies["/v1/trades"] = """{"trades":[],"next":100}"""
        bodies["/v1/liquidations"] = """{"liquidations":[],"next":50}"""

        assertThatThrownBy { client.prints(code, FlowKind.TRADES, 100L, 200L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("gateway trades of $code do not advance past 100")
        assertThatThrownBy { client.prints(code, FlowKind.LIQUIDATIONS, 100L, 200L) }
            .hasMessage("gateway liquidations of $code do not advance past 100")
        assertThat(client.prints(code, FlowKind.TRADES, 200L, 200L)).isEmpty()
        assertThat(paths).containsExactly("/v1/trades", "/v1/liquidations")
    }

    @Test
    fun `prints carry their side and a side that is neither buy nor sell is refused`() {
        bodies["/v1/trades"] =
            """{"trades":[{"id":"a","time":1,"price":"10","size":"2","side":"buy"},
            {"id":"b","time":2,"price":"11","size":"3","side":"sell"}]}"""
        bodies["/v1/liquidations"] = """{"liquidations":[{"id":"c","time":1,"price":"10","size":"2","side":"long"}]}"""

        val prints = client.prints(code, FlowKind.TRADES, 0L, 10L)

        assertThat(prints.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(prints.map { it.id to it.timeMs }).containsExactly("a" to 1L, "b" to 2L)
        assertThat(prints[1].price).isEqualByComparingTo("11")
        assertThat(prints[1].size).isEqualByComparingTo("3")
        assertThatThrownBy { client.prints(code, FlowKind.LIQUIDATIONS, 0L, 10L) }
            .hasMessage("gateway print c has side 'long', not buy or sell")
    }

    @Test
    fun `history by qkt symbol reads the listing once and refuses a symbol the gateway does not list`() {
        declare(DEPTH_CAPABILITY, OPEN_INTEREST_CAPABILITY)
        bodies["/v1/depth"] = """{"depth":[{"time":5,"bids":[["10","1"]],"asks":[["11","2"]]}]}"""
        bodies["/v1/open-interest"] = """{"open_interest":[{"time":5,"open_interest":"42"}]}"""
        bodies["/v1/trades"] = """{"trades":[]}"""
        val depth = GatewayBookDepth(client, symbols, "deribit")
        val interest = GatewayOpenInterest(client, symbols, "deribit")
        val history = GatewayPrintHistory(client, symbols)

        val book = depth.snapshots(symbol, 0L, 10L).single()
        assertThat(book.timeMs).isEqualTo(5L)
        assertThat(book.bids.single().price).isEqualByComparingTo("10")
        assertThat(book.asks.single().amount).isEqualByComparingTo("2")
        assertThat(interest.figures(symbol, 0L, 10L).single().openInterest).isEqualTo(BigDecimal("42"))
        assertThat(history.prints(symbol, FlowKind.TRADES, 0L, 10L)).isEmpty()
        assertThat(paths.count { it == "/v1/instruments" }).`as`("a listed symbol needs no second listing").isEqualTo(1)

        assertThatThrownBy { depth.snapshots("DERIBIT:ETH_USDC_PERPETUAL", 0L, 10L) }
            .hasMessage("DERIBIT:ETH_USDC_PERPETUAL is not in the gateway's listing")
        assertThatThrownBy { interest.figures("DERIBIT:ETH_USDC_PERPETUAL", 0L, 10L) }
            .hasMessage("DERIBIT:ETH_USDC_PERPETUAL is not in the gateway's listing")
        assertThatThrownBy { history.prints("DERIBIT:ETH_USDC_PERPETUAL", FlowKind.TRADES, 0L, 10L) }
            .hasMessage("DERIBIT:ETH_USDC_PERPETUAL is not in the gateway's listing")
    }

    @Test
    fun `a depth level that is not a price and an amount is refused`() {
        declare(DEPTH_CAPABILITY)
        bodies["/v1/depth"] = """{"depth":[{"time":5,"bids":[["10","1","9"]],"asks":[]}]}"""

        assertThatThrownBy { GatewayBookDepth(client, symbols, "deribit").snapshots(symbol, 0L, 10L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("a gateway depth level is [price, amount], not [10, 1, 9]")
    }
}
