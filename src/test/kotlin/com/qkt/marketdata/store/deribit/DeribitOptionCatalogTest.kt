package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.io.IOException
import java.math.BigDecimal
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DeribitOptionCatalogTest {
    private val server = MockWebServer().also { it.start() }
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )
    private var throttleOnce = false

    @AfterEach
    fun teardown() = server.shutdown()

    private fun instrument(
        name: String,
        strike: String,
        type: String,
        expiry: Long,
        counter: String = "USDC",
        kind: String? = "linear",
        quote: String = "USDC",
    ) = """{"instrument_name":"$name","kind":"option",""" + (kind?.let { """"instrument_type":"$it",""" } ?: "") +
        """"settlement_currency":"USDC","quote_currency":"$quote","counter_currency":"$counter",""" +
        """"strike":$strike,"option_type":"$type","expiration_timestamp":$expiry}"""

    private fun result(body: String) = MockResponse().setBody("""{"jsonrpc":"2.0","result":$body}""")

    private fun client(pageSize: Int = 1000): DeribitClient {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (throttleOnce) {
                        throttleOnce = false
                        return MockResponse().setResponseCode(429)
                    }
                    return when {
                        "get_instruments" in path && "expired=false" in path ->
                            result(
                                "[" + instrument("BTC_USDC-27DEC24-90000-P", "90000.0", "put", 1735286400000) + "," +
                                    instrument("ETH_USDC-27DEC24-4000-C", "4000.0", "call", 1735286400000) + "]",
                            )
                        "get_instruments" in path ->
                            result(
                                // The history host omits instrument_type and quotes the base coin; the premium is the counter currency.
                                "[" +
                                    instrument(
                                        "BTC_USDC-27SEP24-60000-C",
                                        "60000.0",
                                        "call",
                                        1727424000000,
                                        kind = null,
                                        quote = "BTC",
                                    ) +
                                    "," +
                                    instrument(
                                        "BTC_USDC-27SEP24-61000-C",
                                        "61000.0",
                                        "call",
                                        1727424000000,
                                        counter = "BTC",
                                    ) +
                                    "," +
                                    instrument("BTC_USDC-27SEP24-62000-C", "62500.0", "call", 1727424000000) + "]",
                            )
                        "get_delivery_prices" in path && "offset=0" in path ->
                            result(
                                """{"data":[{"date":"2024-12-27","delivery_price":95168.7},{"date":"2024-09-27","delivery_price":65422.7}],"records_total":3}""",
                            )
                        "get_delivery_prices" in path ->
                            result(
                                """{"data":[{"date":"2024-09-26","delivery_price":63000.1}],"records_total":3}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val base = server.url("/api/v2").toString()
        return DeribitClient(liveBaseUrl = base, historyBaseUrl = base, pageSize = pageSize, sleep = {})
    }

    @Test
    fun `the catalog keeps the root's linear contracts and warns about each skipped one`() {
        val warnings = mutableListOf<String>()

        val catalog = DeribitOptionCatalog(client(pageSize = 2)).build(root, warnings::add)

        assertThat(catalog.contracts).containsExactly(
            OptionListing("BTC_USDC-27SEP24-60000-C", "60000", "call", 1727424000000),
            OptionListing("BTC_USDC-27DEC24-90000-P", "90000", "put", 1735286400000),
        )
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("BTC_USDC-27SEP24-61000-C").contains("BTC")
        assertThat(warnings[1]).contains("BTC_USDC-27SEP24-62000-C").contains("62500")
        assertThat(catalog.deliveryPrices)
            .containsEntry("2024-12-27", "95168.7")
            .containsEntry("2024-09-27", "65422.7")
            .containsEntry("2024-09-26", "63000.1")
    }

    @Test
    fun `a throttled request is retried`() {
        throttleOnce = true

        assertThat(DeribitOptionCatalog(client()).build(root) {}.contracts).hasSize(2)
    }

    @Test
    fun `a json-rpc error fails the fetch with the venue's message`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse()
                        .setResponseCode(400)
                        .setBody(
                            """{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params","data":{"reason":"invalid index"}}}""",
                        )
            }
        val base = server.url("/api/v2").toString()

        assertThatThrownBy { DeribitOptionCatalog(DeribitClient(base, base, sleep = {})).build(root) {} }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("Invalid params")
            .hasMessageContaining("invalid index")
    }

    @Test
    fun `a server error is retried, and running out of retries fails without a final pause`() {
        var calls = 0
        val pauses = mutableListOf<Long>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls++
                    return MockResponse().setResponseCode(503)
                }
            }
        val base = server.url("/api/v2").toString()

        assertThatThrownBy { DeribitClient(base, base, sleep = { pauses += it }).deliveryPrices("btc_usdc", 0) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("503")
        assertThat(calls).isEqualTo(5)
        assertThat(pauses).hasSize(4)
    }
}
