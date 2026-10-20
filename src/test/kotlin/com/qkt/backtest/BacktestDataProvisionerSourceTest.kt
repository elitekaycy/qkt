package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What the missing-ticks error tells a newcomer when qkt has no download source for the symbol. */
class BacktestDataProvisionerSourceTest {
    @Test
    fun `a symbol qkt cannot download says so instead of blaming --no-fetch`(
        @TempDir tmp: Path,
    ) {
        // #1471: the README quick start ran BTCUSD without --no-fetch and was told to drop it.
        val day = LocalDate.of(2026, 8, 3)
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = null))

        val thrown =
            catchThrowable {
                provisioner.ensure(
                    listOf(ProvisionStream(broker = "BACKTEST", bareSymbol = "BTCUSD")),
                    day,
                    day,
                    fetchEnabled = false,
                    allowIncomplete = false,
                    calendarFor = { TradingCalendar.crypto() },
                )
            }

        assertThat(thrown.message)
            .doesNotContain("drop --no-fetch")
            .contains("qkt has no download source for BTCUSD")
            .contains("qkt data import <file> --as BTCUSD")
    }
}
