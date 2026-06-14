package com.qkt.marketdata.store.macro

import java.nio.file.Path
import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FredSeriesFetcherTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `fetches observations and skips the missing-value marker`(
        @TempDir tmp: Path,
    ) {
        server.enqueue(
            MockResponse().setBody(
                """{"observations":[
                    {"date":"2024-03-01","value":"4.18"},
                    {"date":"2024-03-02","value":"."},
                    {"date":"2024-03-04","value":"4.21"}
                ]}""",
            ),
        )
        val store = MacroSeriesStore(tmp)
        val fetcher =
            FredSeriesFetcher(
                store = store,
                apiKey = null,
                baseUrl = server.url("/fred").toString().trimEnd('/'),
            )

        fetcher.fetch("DGS10", LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 4))

        val read = store.read("DGS10", LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 4))
        assertThat(read.map { it.date }).containsExactly(
            LocalDate.of(2024, 3, 1),
            LocalDate.of(2024, 3, 4),
        )
        assertThat(read[0].value).isEqualByComparingTo("4.18")
        assertThat(read[1].value).isEqualByComparingTo("4.21")
        // The request carried the series id and the date window.
        val path = server.takeRequest().path.orEmpty()
        assertThat(path).contains("series_id=DGS10")
        assertThat(path).contains("observation_start=2024-03-01")
    }
}
