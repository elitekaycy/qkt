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
        val noVenue = CalendarResolver(f.registry) { null }

        val options = backtestCalendar(listOf("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d", f.symbol), f.registry, noVenue)
        assertThat(options.forSymbol(f.symbol)).isEqualTo(crypto)
        assertThat(options.runSymbols).containsExactly(f.symbol)
        val fx =
            backtestCalendar(
                listOf("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d", "EXNESS:EURUSD"),
                NoopInstrumentRegistry,
                noVenue,
            )
        assertThat(fx.forSymbol("EXNESS:EURUSD")).isEqualTo(TradingCalendar.fxDefault())
        assertThat(fx.runSymbols).containsExactly("EXNESS:EURUSD")
    }

    @Test
    fun `a live strategy's calendar skips its read-only feeds and asks the venue for its first traded symbol`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        val asked = mutableListOf<String>()
        val venue = TradingCalendar.fxDefault()

        val symbols = listOf("OPTIONS:DERIBIT.BTC_USDC", "CHAIN:DERIBIT.BTC_USDC.atm_iv.30d", f.symbol)

        val calendar =
            strategyCalendar(symbols, f.registry) {
                asked += it
                venue
            }

        assertThat(calendar.forSymbol(f.symbol)).isEqualTo(venue)
        assertThat(asked).containsExactly(f.symbol)
        val feedsOnly = strategyCalendar(listOf("OPTIONS:DERIBIT.BTC_USDC"), f.registry) { venue }
        assertThat(feedsOnly.name).isEqualTo("crypto")
        assertThat(feedsOnly.runSymbols).isEmpty()
    }
}
