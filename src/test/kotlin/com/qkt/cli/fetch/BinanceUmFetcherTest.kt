package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.store.binance.BinanceVisionClient
import java.io.ByteArrayOutputStream
import java.time.Instant
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

class BinanceUmFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: BinanceUmFetcher

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        fetcher = BinanceUmFetcher(BinanceVisionClient(filesBaseUrl = server.url("/").toString().trimEnd('/')))
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun day(date: String) =
        TimeRange(Instant.parse("${date}T00:00:00Z"), Instant.parse("${date}T00:00:00Z").plusSeconds(86_400))

    private fun zip(text: String): Buffer =
        Buffer().write(
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { z ->
                        z.putNextEntry(ZipEntry("k.csv"))
                        z.write(text.toByteArray())
                        z.closeEntry()
                    }
                }.toByteArray(),
        )

    @Test
    fun `a day's klines are read from that day's file`() {
        server.enqueue(MockResponse().setBody(zip("1725235200000,1,2,0.5,1.5,3,1725235259999,0,1,0,0,0\n")))
        val bars = fetcher.fetch("BTCUSDT_240927", TimeWindow.ONE_MINUTE, day("2024-09-02"))
        assertThat(bars.single().close).isEqualByComparingTo("1.5")
        assertThat(bars.single().symbol).isEqualTo("BINANCE_UM:BTCUSDT_240927")
        assertThat(server.takeRequest().path)
            .isEqualTo("/data/futures/um/daily/klines/BTCUSDT_240927/1m/BTCUSDT_240927-1m-2024-09-02.zip")
    }

    @Test
    fun `a missing day is empty`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(fetcher.fetch("BTCUSDT_240927", TimeWindow.ONE_MINUTE, day("2024-01-01"))).isEmpty()
    }

    @Test
    fun `only days after expiry are expected to be empty`() {
        assertThat(fetcher.isExpectedEmpty("BTCUSDT_240927", day("2024-09-28"))).isTrue()
        assertThat(fetcher.isExpectedEmpty("BTCUSDT_240927", day("2024-09-27"))).isFalse()
        assertThat(fetcher.isExpectedEmpty("BTCUSDT_240927", day("2024-06-01"))).isFalse()
        assertThat(fetcher.isExpectedEmpty("BTCUSDT", day("2024-06-01"))).isFalse()
    }

    @Test
    fun `a timeframe Binance does not publish is refused`() {
        assertThatThrownBy { fetcher.fetch("BTCUSDT_240927", TimeWindow(7 * 60_000L), day("2024-09-02")) }
            .hasMessageContaining("1m")
    }

    @Test
    fun `bars from delivery onwards are not kept`() {
        // 2024-09-27 07:59 and 08:00 UTC; the contract delivers at 08:00.
        server.enqueue(
            MockResponse().setBody(
                zip(
                    "1727423940000,65386,65426,65386,65426,1,1727423999999,0,1,0,0,0\n1727424000000,65426,65426,65426,65426,0,1727424059999,0,0,0,0,0\n",
                ),
            ),
        )
        val bars = fetcher.fetch("BTCUSDT_240927", TimeWindow.ONE_MINUTE, day("2024-09-27"))
        assertThat(bars.map { it.startTime }).containsExactly(1727423940000L)
    }

    @Test
    fun `an empty archive is an empty day`() {
        server.enqueue(
            MockResponse().setBody(
                Buffer().write(
                    ByteArrayOutputStream()
                        .also {
                            ZipOutputStream(it).close()
                        }.toByteArray(),
                ),
            ),
        )
        assertThat(fetcher.fetch("BTCUSDT_240927", TimeWindow.ONE_MINUTE, day("2024-09-02"))).isEmpty()
    }
}
