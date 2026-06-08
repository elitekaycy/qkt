package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestDataProvisionerTest {
    /** Fetcher that writes a full 24-hour day for the listed days, and an empty file otherwise. */
    private class FullDayFetcher(
        private val fullDays: Set<LocalDate>,
    ) : DataFetcher {
        val fetched = mutableListOf<LocalDate>()

        override fun fetch(
            symbol: String,
            day: LocalDate,
            target: Path,
        ) {
            fetched += day
            Files.createDirectories(target.parent)
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
            if (day in fullDays) {
                for (h in 0..23) sb.append("${dayStart + h * 3_600_000L},EURUSD,,,1.10,1.10,1.0,1.0\n")
            }
            GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
        }
    }

    private fun stream(sym: String) = ProvisionStream(broker = "BACKTEST", bareSymbol = sym)

    @Test
    fun `fetches missing days then passes validation`(
        @TempDir tmp: Path,
    ) {
        val day = LocalDate.of(2024, 3, 6) // Wednesday
        val fetcher = FullDayFetcher(fullDays = setOf(day))
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = fetcher))

        provisioner.ensure(
            streams = listOf(stream("EURUSD")),
            from = day,
            to = day,
            fetchEnabled = true,
            allowIncomplete = false,
            calendarFor = { TradingCalendar.fxDefault() },
        )

        assertThat(fetcher.fetched).contains(day)
    }

    @Test
    fun `a genuine hole hard-fails unless allowed`(
        @TempDir tmp: Path,
    ) {
        val day = LocalDate.of(2024, 3, 6)
        val fetcher = FullDayFetcher(fullDays = emptySet()) // writes empty files -> missing
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = fetcher))

        assertThatThrownBy {
            provisioner.ensure(
                listOf(stream("EURUSD")),
                day,
                day,
                fetchEnabled = true,
                allowIncomplete = false,
                calendarFor = { TradingCalendar.fxDefault() },
            )
        }.isInstanceOf(IncompleteDataException::class.java).hasMessageContaining("EURUSD")

        // With the override it returns normally.
        provisioner.ensure(
            listOf(stream("EURUSD")),
            day,
            day,
            fetchEnabled = true,
            allowIncomplete = true,
            calendarFor = { TradingCalendar.fxDefault() },
        )
    }
}
