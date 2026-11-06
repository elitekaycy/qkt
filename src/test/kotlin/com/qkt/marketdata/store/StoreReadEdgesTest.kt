package com.qkt.marketdata.store

import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateArtifactReader
import com.qkt.marketdata.store.macro.PolicyRateSeries
import com.qkt.marketdata.store.macro.PolicyRateSeriesFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoreReadEdgesTest {
    @TempDir
    lateinit var dir: Path

    private val reader = PolicyRateArtifactReader(OkHttpClient())

    @Test
    fun `an epoch instant belongs to its UTC day`() {
        assertThat(LocalBarStore.dayOf(Instant.parse("2026-03-04T23:59:59Z").toEpochMilli()))
            .isEqualTo(LocalDate.of(2026, 3, 4))
    }

    @Test
    fun `a day file of an unknown kind is inspected as an unknown format`() {
        val file = Files.writeString(dir.resolve("2026-01-05.txt"), "")

        assertThat(inspectDatasetDayFile(dir, LocalDate.of(2026, 1, 5), file).format).isEqualTo("unknown")
    }

    @Test
    fun `a policy-rate artifact is read from a file URI and refused on another scheme`() {
        val file = Files.write(dir.resolve("ocr.xlsx"), byteArrayOf(1, 2, 3))

        assertThat(reader.read(file.toUri().toString(), "RBNZ")).containsExactly(1, 2, 3)
        assertThatThrownBy { reader.read("ftp://example.com/ocr.xlsx", "RBNZ") }
            .hasMessageContaining("unsupported URI scheme 'ftp'")
    }

    @Test
    fun `an empty policy-rate artifact file is refused`() {
        val file = Files.write(dir.resolve("empty.xlsx"), ByteArray(0))

        assertThatThrownBy { reader.read(file.toString(), "RBA") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("RBA policy-rate artifact must contain 1..")
    }

    @Test
    fun `a refused or oversized policy-rate download is refused naming the url`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10 * 1024 * 1024 + 1))))
            server.start()
            val url = server.url("/ocr.xlsx").toString()

            assertThatThrownBy {
                reader.read(
                    url,
                    "RBNZ",
                )
            }.hasMessage("RBNZ policy-rate fetch failed: HTTP 503 from $url")
            assertThatThrownBy {
                reader.read(
                    url,
                    "RBNZ",
                )
            }.hasMessageContaining("artifact exceeds").hasMessageContaining(url)
        }
    }

    @Test
    fun `a cash-rate fetch reads the RBA source`() {
        val missing = dir.resolve("absent-rba.xlsx").toString()
        val fetcher = PolicyRateSeriesFetcher(MacroSeriesStore(dir.resolve("store")), rbaSource = missing)

        assertThatThrownBy {
            fetcher.fetch(
                PolicyRateSeries.RBA_CASH_RATE,
                LocalDate.of(2024, 3, 1),
                LocalDate.of(2024, 3, 5),
            )
        }.hasMessageContaining("RBA policy-rate artifact is not a regular file: $missing")
    }
}
