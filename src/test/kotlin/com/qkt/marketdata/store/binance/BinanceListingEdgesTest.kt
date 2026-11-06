package com.qkt.marketdata.store.binance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BinanceListingEdgesTest {
    private val server = MockWebServer().also { it.start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val client =
        BinanceVisionClient(filesBaseUrl = base, listingBaseUrl = "$base/list", apiBaseUrl = "$base/api")

    @AfterEach
    fun teardown() = server.shutdown()

    private fun truncatedAt(marker: String) =
        MockResponse().setBody(
            "<ListBucketResult><IsTruncated>true</IsTruncated><NextMarker>$marker</NextMarker>" +
                "<CommonPrefixes><Prefix>data/x/$marker/</Prefix></CommonPrefixes></ListBucketResult>",
        )

    @Test
    fun `a listing whose next page repeats the same marker is refused instead of looping`() {
        server.enqueue(truncatedAt("m1"))
        server.enqueue(truncatedAt("m1"))

        assertThatThrownBy { client.listPrefixes("data/x/") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("does not advance past m1")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an unreadable delivery-price answer leaves prices out with a warning`() {
        server.enqueue(
            MockResponse().setBody(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<CommonPrefixes><Prefix>data/x/BTCUSDT_240927/</Prefix></CommonPrefixes></ListBucketResult>",
            ),
        )
        server.enqueue(MockResponse().setBody("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"))
        server.enqueue(MockResponse().setBody("{not json"))
        val warnings = mutableListOf<String>()

        val built = BinanceContractCatalog(client).build("BINANCE_UM:BTCUSDT", warnings::add)

        assertThat(built.contracts.single().deliveryPrice).isNull()
        assertThat(warnings.single()).contains("delivery")
    }
}
