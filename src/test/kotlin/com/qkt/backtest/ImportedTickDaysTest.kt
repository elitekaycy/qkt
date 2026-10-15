package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.ingest.store.ImportEntry
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportedTickDaysTest {
    private val day = LocalDate.of(2024, 3, 6)

    private val entry =
        ImportEntry(
            id = "abc",
            inputs = emptyList(),
            preset = "mt5-ticks",
            kind = "ticks",
            symbol = "EURUSD",
            rows = 6,
            records = 6,
            qkt = "t",
            importedAt = "",
        )

    /** An imported Wednesday with ticks only in its first six hours: a hole the validator sees. */
    private fun seedImportedHoleyDay(root: Path): Path {
        val dir = root.resolve("symbols/EURUSD").also { Files.createDirectories(it) }
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val body = (0..5).joinToString("") { "${start + it * 3_600_000L},EURUSD,,,1.10,1.10,1.0,1.0\n" }
        val file = dir.resolve("$day.csv.gz")
        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter().use {
            it.write(
                CsvTickFeed.EXPECTED_HEADER + "\n" + body,
            )
        }
        ManifestStore(
            root,
        ).write(Manifest(symbol = "EURUSD", ranges = listOf(DayRange(day.toString(), day.plusDays(1).toString()))))
        ImportProvenance.record(dir, entry, listOf(day.toString()))
        return file
    }

    @Test
    fun `a backtest never replaces an imported day with a fetch, holes and all`(
        @TempDir root: Path,
    ) {
        val file = seedImportedHoleyDay(root)
        val before = Files.readAllBytes(file)
        val fetched = mutableListOf<LocalDate>()
        val fetcher =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) {
                    fetched += day
                }
            }

        BacktestDataProvisioner(DefaultDataStore(root, fetcher)).ensure(
            streams = listOf(ProvisionStream("BACKTEST", "EURUSD")),
            from = day,
            to = day,
            fetchEnabled = true,
            allowIncomplete = true,
            calendarFor = { TradingCalendar.fxDefault() },
        )

        assertThat(fetched).isEmpty()
        assertThat(Files.readAllBytes(file)).isEqualTo(before)
    }

    @Test
    fun `a window mixing imported and fetched days says so`(
        @TempDir root: Path,
    ) {
        seedImportedHoleyDay(root)
        val days = ImportedTickDays(root.resolve("symbols/EURUSD"))

        assertThat(days.mixLine("EURUSD", listOf(day, day.plusDays(1))))
            .isEqualTo("EURUSD ticks: 1 days imported (mt5-ticks), 1 days from other sources")
        assertThat(days.mixLine("EURUSD", listOf(day))).isNull()
    }
}
