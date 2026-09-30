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
