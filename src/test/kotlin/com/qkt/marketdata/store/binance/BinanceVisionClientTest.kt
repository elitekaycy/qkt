package com.qkt.marketdata.store.binance

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BinanceVisionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BinanceVisionClient

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        val base = server.url("/").toString().trimEnd('/')
        client = BinanceVisionClient(filesBaseUrl = base, listingBaseUrl = "$base/list", apiBaseUrl = "$base/api")
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun zipOf(
        name: String,
        text: String,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()

    @Test
    fun `a file downloads and unzips`() {
        server.enqueue(MockResponse().setBody(Buffer().write(zipOf("a.csv", "x,y\n1,2\n"))))
        val bytes = client.download("data/futures/um/daily/klines/X/1m/X-1m-2024-09-02.zip")
        assertThat(client.unzipSingle(requireNotNull(bytes))).isEqualTo("x,y\n1,2\n")
        assertThat(server.takeRequest().path).isEqualTo("/data/futures/um/daily/klines/X/1m/X-1m-2024-09-02.zip")
    }

    @Test
    fun `a missing file is null and other errors throw`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.download("data/missing.zip")).isNull()
        assertThatThrownBy { client.download("data/broken.zip") }.hasMessageContaining("500")
    }

    @Test
    fun `a truncated listing is followed to the last page`() {
        server.enqueue(
            MockResponse().setBody(
                "<ListBucketResult><Prefix>data/p/B_</Prefix><IsTruncated>true</IsTruncated>" +
                    "<NextMarker>data/p/B_2/</NextMarker>" +
                    "<CommonPrefixes><Prefix>data/p/B_1/</Prefix></CommonPrefixes>" +
                    "<CommonPrefixes><Prefix>data/p/B_2/</Prefix></CommonPrefixes></ListBucketResult>",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<CommonPrefixes><Prefix>data/p/B_3/</Prefix></CommonPrefixes></ListBucketResult>",
            ),
        )
        assertThat(client.listPrefixes("data/p/B_")).containsExactly("data/p/B_1/", "data/p/B_2/", "data/p/B_3/")
        assertThat(server.takeRequest().path).contains("prefix=data%2Fp%2FB_").contains("delimiter=%2F")
        assertThat(server.takeRequest().path).contains("marker=data%2Fp%2FB_2%2F")
    }

    @Test
    fun `delivery prices keep their exact decimal text`() {
        server.enqueue(MockResponse().setBody("""[{"deliveryTime":1727424000000,"deliveryPrice":65528.10000000}]"""))
        assertThat(client.deliveryPrices("BTCUSDT")).containsEntry(1727424000000L, "65528.10000000")
        assertThat(server.takeRequest().path).isEqualTo("/api/futures/data/delivery-price?pair=BTCUSDT")
    }
}
