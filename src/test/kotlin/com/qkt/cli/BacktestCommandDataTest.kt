package com.qkt.cli

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandDataTest {
    /** Writes a full 24-hour day so the provisioner's completeness check passes. */
    private class FullDayFetcher : DataFetcher {
        override fun fetch(
            symbol: String,
            day: LocalDate,
            target: Path,
        ) {
            Files.createDirectories(target.parent)
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
            for (h in 0..23) sb.append("${dayStart + h * 3_600_000L},XAUUSD,,,2345.65,2345.67,1.0,1.0\n")
            GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
        }
    }

    @Test
    fun `auto-fetches and runs without a separate fetch step`(
        @TempDir tmp: Path,
    ) {
        val strat = tmp.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 5m
            RULES
              WHEN NOW.minute_utc = 0 THEN LOG "tick"
            """.trimIndent(),
        )
        val args =
            Args(
                arrayOf(
                    "backtest",
                    strat.toString(),
                    "--from",
                    "2024-03-06",
                    "--to",
                    "2024-03-07",
                    "--data-root",
                    tmp.resolve("data").toString(),
                ),
            )
        val code = BacktestCommand(args, fetcherOverride = FullDayFetcher()).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        // Data landed under the bare-keyed tick store.
        assertThat(Files.exists(tmp.resolve("data/symbols/XAUUSD/2024-03-06.csv.gz"))).isTrue()
    }
}
