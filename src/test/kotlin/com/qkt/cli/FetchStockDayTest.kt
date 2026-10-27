package com.qkt.cli

import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A stock day from Exness ends at 15:45 New York, and `qkt fetch` keeps it because the stock's folder puts it on the
 * stock session. On the FX week, which a stock had before, those same bars stop while the market is open and the
 * day is never stored.
 */
class FetchStockDayTest {
    // 2026-10-26 (Monday, New York summer time): 10:00-19:44 UTC, one bar a minute, as Exness served AAPLm.
    private val bars =
        (0 until 585).joinToString(",", "[", "]") { m ->
            """{"time":"${Instant.parse("2026-10-26T10:00:00Z").plusSeconds(m * 60L)}","open":346.1,"high":346.2,""" +
                """"low":346.0,"close":346.1,"tick_volume":40,"spread":17,"real_volume":0}"""
        }

    private fun fetch(
        tmp: Path,
        folder: String,
    ): Boolean {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path.orEmpty().startsWith("/symbol_info/") ->
                            MockResponse().setBody("""{"point":"0.01","path":"Standard\\\\$folder\\\\AAPLm"}""")
                        request.path.orEmpty().contains("2026-10-26T00") -> MockResponse().setBody(bars)
                        else -> MockResponse().setBody("[]")
                    }
            }
        server.start()
        try {
            val config = tmp.resolve("qkt.config.yaml")
            Files.writeString(
                config,
                "brokers:\n  exness:\n    type: mt5\n    gateway_url: ${server.url("/").toString().trimEnd('/')}\n",
            )
            val args =
                Args(
                    arrayOf(
                        "fetch",
                        "EXNESS:AAPL",
                        "--tf",
                        "1m",
                        "--from",
                        "2026-10-26",
                        "--to",
                        "2026-10-26",
                        "--config",
                        config.toString(),
                        "--data-root",
                        tmp.toString(),
                    ),
                )
            assertThat(FetchCommand(args).run()).isEqualTo(ExitCodes.SUCCESS)
            return LocalBarStore(tmp).hasDay("EXNESS", "AAPL", "1m", LocalDate.parse("2026-10-26"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a stock's day ending at 15_45 New York is complete and stored`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp, "Stocks")).isTrue()
        assertThat(LocalBarStore(tmp).readDay("EXNESS", "AAPL", "1m", LocalDate.parse("2026-10-26"))).hasSize(585)
    }

    @Test
    fun `the same bars filed as forex stop while that market is open and are not stored`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp, "Forex")).isFalse()
    }
}
