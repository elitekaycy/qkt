package com.qkt.cli

import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.OptionChainFixture
import com.qkt.instrument.NoopInstrumentRegistry
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCalendarTest {
    @Test
    fun `an option contract trades on the round-the-clock calendar and an analytics stream never picks one`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        val crypto = TradingCalendar.crypto()

        assertThat(
            backtestCalendar(listOf("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d", f.symbol), f.registry),
        ).isEqualTo(crypto)
        assertThat(
            backtestCalendar(listOf("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d", "EXNESS:EURUSD"), NoopInstrumentRegistry),
        ).isEqualTo(backtestCalendar(listOf("EXNESS:EURUSD"), NoopInstrumentRegistry))
    }
}
