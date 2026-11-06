package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestDataProvisionerFetchErrorTest {
    @Test
    fun `a fetcher that fails its own checks is reported as incomplete data`(
        @TempDir root: Path,
    ) {
        val fetcher =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ): Unit = error("no ticks published for $day")
            }
        val day = LocalDate.of(2024, 3, 4)

        assertThatThrownBy {
            BacktestDataProvisioner(DefaultDataStore(root = root, fetcher = fetcher)) {}.ensure(
                streams = listOf(ProvisionStream(broker = "BACKTEST", bareSymbol = "EURUSD")),
                from = day,
                to = day,
                fetchEnabled = true,
                allowIncomplete = false,
                calendarFor = { TradingCalendar.fxDefault() },
            )
        }.isInstanceOf(IncompleteDataException::class.java)
            .hasMessage("could not fetch ticks for EURUSD: no ticks published for 2024-03-04")
    }
}
