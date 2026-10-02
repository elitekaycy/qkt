package com.qkt.marketdata.store.binance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BinanceContractCatalogTest {
    private lateinit var server: MockWebServer
    private lateinit var catalog: BinanceContractCatalog

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        val base = server.url("/").toString().trimEnd('/')
        catalog =
            BinanceContractCatalog(
                BinanceVisionClient(filesBaseUrl = base, listingBaseUrl = "$base/list", apiBaseUrl = "$base/api"),
            )
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun listing(vararg codes: String) =
        MockResponse().setBody(
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                codes.joinToString("") { "<CommonPrefixes><Prefix>data/x/$it/</Prefix></CommonPrefixes>" } +
                "</ListBucketResult>",
        )

    @Test
    fun `a contract with daily files but no monthly file yet is included`() {
        server.enqueue(listing("BTCUSDT_261225"))
        server.enqueue(listing("BTCUSDT_261225", "BTCUSDT_270326"))
        server.enqueue(MockResponse().setBody("[]"))
        val built = catalog.build("BINANCE_UM:BTCUSDT")
        assertThat(built.contracts.map { it.symbol }).containsExactly("BTCUSDT_261225", "BTCUSDT_270326")
        assertThat(server.takeRequest().path).contains("monthly")
        assertThat(server.takeRequest().path).contains("daily")
    }

    @Test
    fun `an unavailable delivery-price endpoint leaves prices out instead of failing`() {
        server.enqueue(listing("BTCUSDT_240927"))
        server.enqueue(listing())
        server.enqueue(MockResponse().setResponseCode(451))
        val warnings = mutableListOf<String>()
        val built = catalog.build("BINANCE_UM:BTCUSDT", warnings::add)
        assertThat(built.contracts.single().deliveryPrice).isNull()
        assertThat(warnings.single()).contains("delivery").contains("451")
    }

    @Test
    fun `quarterlies are listed with expiries and known delivery prices`() {
        server.enqueue(
            MockResponse().setBody(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<Prefix>data/futures/um/monthly/klines/BTCUSDT_</Prefix>" +
                    "<CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_241227/</Prefix></CommonPrefixes>" +
                    "<CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_240927/</Prefix></CommonPrefixes>" +
                    "<CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_PERP/</Prefix></CommonPrefixes>" +
                    "</ListBucketResult>",
            ),
        )
        server.enqueue(listing())
        // The endpoint stamps each delivery at 00:00 UTC of the delivery date (2024-09-27), while the
        // contract itself settles at 08:00 UTC that day.
        server.enqueue(MockResponse().setBody("""[{"deliveryTime":1727395200000,"deliveryPrice":65528.1}]"""))

        val built = catalog.build("BINANCE_UM:BTCUSDT")

        assertThat(built.root).isEqualTo("BINANCE_UM:BTCUSDT")
        assertThat(built.contracts.map { it.symbol }).containsExactly("BTCUSDT_240927", "BTCUSDT_241227")
        assertThat(built.contracts[0].expiryMs).isEqualTo(1727424000000L)
        assertThat(built.contracts[0].deliveryPrice).isEqualTo("65528.1")
        assertThat(built.contracts[1].deliveryPrice).isNull()
    }

    @Test
    fun `a root on another venue is refused`() {
        assertThatThrownBy { catalog.build("CME:ES") }.hasMessageContaining("BINANCE_UM")
    }
}
