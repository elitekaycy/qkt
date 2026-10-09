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
import org.assertj.core.api.Assertions.catchThrowable
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

    @Test
    fun `a fetch that keeps failing is reported as incomplete data, not a crash`(
        @TempDir tmp: Path,
    ) {
        val failing =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ): Unit = throw java.io.IOException("dukascopy fetch failed after 3 attempts")
            }
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = failing))
        val day = LocalDate.of(2024, 3, 6)

        assertThatThrownBy {
            provisioner.ensure(listOf(stream("EURUSD")), day, day, true, false, calendarFor = { TradingCalendar.fxDefault() })
        }.isInstanceOf(IncompleteDataException::class.java)
            .hasMessageContaining("could not fetch ticks for EURUSD")
    }

    @Test
    fun `a partial gap is reported as compact ranges with the tick dir it searched`(
        @TempDir tmp: Path,
    ) {
        val from = LocalDate.of(2024, 3, 4) // Monday
        val to = LocalDate.of(2024, 3, 8) // Friday
        val fetcher = FullDayFetcher(fullDays = setOf(from, from.plusDays(1)))
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = fetcher))

        val thrown =
            catchThrowable {
                provisioner.ensure(
                    listOf(stream("EURUSD")),
                    from,
                    to,
                    fetchEnabled = true,
                    allowIncomplete = false,
                    calendarFor = { TradingCalendar.fxDefault() },
                )
            }

        assertThat(thrown).isInstanceOf(IncompleteDataException::class.java)
        assertThat(thrown).hasMessageContaining("incomplete EURUSD tick data for 2024-03-04 to 2024-03-08 (2 of 5 trading days)")
        assertThat(thrown).hasMessageContaining("Missing: 2024-03-06 to 2024-03-08 (1 range, 3 days)")
        assertThat(thrown).hasMessageContaining("Looked in:")
        assertThat(thrown).hasMessageContaining("has 2024-03-04 to 2024-03-08")
        assertThat(thrown.message).doesNotContain("drop --no-fetch")
    }

    @Test
    fun `a total miss names the empty tick dir and the no-fetch blocker`(
        @TempDir tmp: Path,
    ) {
        val day = LocalDate.of(2024, 3, 6)
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = null))

        val thrown =
            catchThrowable {
                provisioner.ensure(
                    listOf(stream("EURUSD")),
                    day,
                    day,
                    fetchEnabled = false,
                    allowIncomplete = false,
                    calendarFor = { TradingCalendar.fxDefault() },
                )
            }

        assertThat(thrown).isInstanceOf(IncompleteDataException::class.java)
        assertThat(thrown).hasMessageContaining("no EURUSD tick data for 2024-03-06 to 2024-03-06 (0 of 1 trading days)")
        assertThat(thrown).hasMessageContaining("(empty — no tick days stored)")
        assertThat(thrown).hasMessageContaining("drop --no-fetch")
        assertThat(thrown.message).doesNotContain("Missing:")
    }

    @Test
    fun `a supplied bars hint surfaces the bars suggestion`(
        @TempDir tmp: Path,
    ) {
        val day = LocalDate.of(2024, 3, 6)
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = null))

        val thrown =
            catchThrowable {
                provisioner.ensure(
                    listOf(stream("EURUSD")),
                    day,
                    day,
                    fetchEnabled = false,
                    allowIncomplete = false,
                    calendarFor = { TradingCalendar.fxDefault() },
                    barsLineFor = { "Bars exist for this window (15m, 1 of 1 days): rerun with --bars to use them." },
                )
            }

        assertThat(thrown).isInstanceOf(IncompleteDataException::class.java)
        assertThat(thrown).hasMessageContaining("rerun with --bars")
    }
}
