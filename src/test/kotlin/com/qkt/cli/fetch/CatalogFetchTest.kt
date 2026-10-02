package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.marketdata.store.binance.BinanceContractCatalog
import com.qkt.marketdata.store.binance.BinanceQuarterly
import com.qkt.marketdata.store.binance.BinanceVisionClient
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogFetchTest {
    @Test
    fun `a binance root's catalog is written to the store`(
        @TempDir dir: Path,
    ) {
        val server = MockWebServer().also { it.start() }
        server.enqueue(
            MockResponse().setBody(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_240927/</Prefix></CommonPrefixes></ListBucketResult>",
            ),
        )
        server.enqueue(MockResponse().setBody("[]"))
        val base = server.url("/").toString().trimEnd('/')
        val client = BinanceVisionClient(filesBaseUrl = base, listingBaseUrl = "$base/list", apiBaseUrl = "$base/api")

        val code = CatalogFetch.run("BINANCE_UM:BTCUSDT", dir) { BinanceContractCatalog(client) }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(
            ContractCatalogStore(dir).read("BINANCE_UM:BTCUSDT")?.contracts?.map {
                it.symbol
            },
        ).containsExactly("BTCUSDT_240927")
        server.shutdown()
    }

    @Test
    fun `a stored delivery price survives a refetch while the delivery endpoint is down`(
        @TempDir dir: Path,
    ) {
        val expiry = BinanceQuarterly.expiryMs("BTCUSDT_240927")!!
        ContractCatalogStore(dir).write(
            ContractCatalog("BINANCE_UM:BTCUSDT", listOf(ListedContract("BTCUSDT_240927", expiry, "65000.1"))),
        )
        val server = MockWebServer().also { it.start() }
        server.enqueue(
            MockResponse().setBody(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_240927/</Prefix></CommonPrefixes>" +
                    "<CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT_241227/</Prefix></CommonPrefixes>" +
                    "</ListBucketResult>",
            ),
        )
        server.enqueue(MockResponse().setBody("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"))
        server.enqueue(MockResponse().setResponseCode(503))
        val base = server.url("/").toString().trimEnd('/')
        val client = BinanceVisionClient(filesBaseUrl = base, listingBaseUrl = "$base/list", apiBaseUrl = "$base/api")

        val code = CatalogFetch.run("BINANCE_UM:BTCUSDT", dir) { BinanceContractCatalog(client) }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(
            ContractCatalogStore(dir).read("BINANCE_UM:BTCUSDT")?.contracts?.map { it.symbol to it.deliveryPrice },
        ).containsExactly("BTCUSDT_240927" to "65000.1", "BTCUSDT_241227" to null)
        server.shutdown()
    }

    @Test
    fun `a venue without a catalog source is a user error`(
        @TempDir dir: Path,
    ) {
        assertThat(CatalogFetch.run("CME:ES", dir) { null }).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `a listing failure is a user error, not a crash`(
        @TempDir dir: Path,
    ) {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setResponseCode(429))
        val base = server.url("/").toString().trimEnd('/')
        val client = BinanceVisionClient(filesBaseUrl = base, listingBaseUrl = "$base/list", apiBaseUrl = "$base/api")

        assertThat(
            CatalogFetch.run("BINANCE_UM:BTCUSDT", dir) {
                BinanceContractCatalog(client)
            },
        ).isEqualTo(ExitCodes.USER_ERROR)
        server.shutdown()
    }
}
