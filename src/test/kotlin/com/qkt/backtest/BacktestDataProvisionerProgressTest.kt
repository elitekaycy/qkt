package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DefaultDataStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A first backtest downloads its data day by day, says so as it goes, and keeps every day it finished:
 * a fetch interrupted on its third day (a rate limit, a dropped connection, Ctrl-C) leaves the first two
 * days cached, so the next run fetches only what is still missing.
 */
class BacktestDataProvisionerProgressTest {
    /** Writes a full day of hourly ticks; throws on [failOn] like a download that gave up. */
    private class Fetcher(
        private val failOn: LocalDate? = null,
    ) : DataFetcher {
        val fetched = mutableListOf<LocalDate>()

        override fun fetch(
            symbol: String,
            day: LocalDate,
            target: Path,
        ) {
            if (day == failOn) throw IOException("dukascopy fetch failed after 5 attempts per host")
            fetched += day
            Files.createDirectories(target.parent)
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
            for (h in 0..23) sb.append("${dayStart + h * 3_600_000L},EURUSD,,,1.10,1.10,1.0,1.0\n")
            GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
        }
    }

    private val monday = LocalDate.of(2024, 3, 4)
    private val wednesday = LocalDate.of(2024, 3, 6)

    private fun ensure(
        root: Path,
        fetcher: Fetcher,
        lines: MutableList<String>,
    ) = BacktestDataProvisioner(DefaultDataStore(root = root, fetcher = fetcher)) { lines += it }.ensure(
        streams = listOf(ProvisionStream(broker = "BACKTEST", bareSymbol = "EURUSD")),
        from = monday,
        to = wednesday,
        fetchEnabled = true,
        allowIncomplete = false,
        calendarFor = { TradingCalendar.fxDefault() },
    )

    @Test
    fun `each missing day is announced before it downloads`(
        @TempDir tmp: Path,
    ) {
        val lines = mutableListOf<String>()
        ensure(tmp, Fetcher(), lines)

        assertThat(lines).containsSubsequence(
            "qkt: fetching EURUSD ticks for 2024-03-04 (1 of 3)",
            "qkt: fetching EURUSD ticks for 2024-03-05 (2 of 3)",
            "qkt: fetching EURUSD ticks for 2024-03-06 (3 of 3)",
            "qkt: tick coverage EURUSD 3/3 trading days",
        )
    }

    @Test
    fun `an interrupted fetch keeps the days it finished and the next run fetches only the rest`(
        @TempDir tmp: Path,
    ) {
        val lines = mutableListOf<String>()
        assertThatThrownBy { ensure(tmp, Fetcher(failOn = wednesday), lines) }
            .isInstanceOf(IncompleteDataException::class.java)

        val rerun = Fetcher()
        ensure(tmp, rerun, mutableListOf())

        assertThat(rerun.fetched).containsExactly(wednesday)
    }

    @Test
    fun `cached days print nothing and fetch nothing`(
        @TempDir tmp: Path,
    ) {
        ensure(tmp, Fetcher(), mutableListOf())
        val lines = mutableListOf<String>()
        val again = Fetcher()
        ensure(tmp, again, lines)

        assertThat(again.fetched).isEmpty()
        assertThat(lines).noneMatch { it.startsWith("qkt: fetching") }
    }
}
