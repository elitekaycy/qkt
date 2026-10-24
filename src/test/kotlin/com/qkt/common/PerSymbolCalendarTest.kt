package com.qkt.common

import com.qkt.candles.TimeWindow
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerSymbolCalendarTest {
    private val byName = mapOf("EURUSD" to TradingCalendar.fxDefault(), "BTCUSD" to TradingCalendar.crypto())

    private fun calendar(vararg symbols: String) =
        PerSymbolCalendar(byName.getValue(symbols.first()), symbols.toList()) { byName[it] ?: TradingCalendar.nyse() }

    @Test
    fun `each symbol's session questions are answered by its own calendar whatever the stream order`() {
        val saturday = Instant.parse("2024-06-15T12:00:00Z")
        for (cal in listOf(calendar("EURUSD", "BTCUSD"), calendar("BTCUSD", "EURUSD"))) {
            assertThat(cal.isInSession("EURUSD", saturday)).isFalse()
            assertThat(cal.isInSession("BTCUSD", saturday)).isTrue()
            val wednesday = Instant.parse("2024-06-12T12:00:00Z")
            assertThat(cal.sessionRange("EURUSD", wednesday).to).isEqualTo(Instant.parse("2024-06-12T21:00:00Z"))
            assertThat(cal.sessionRange("BTCUSD", wednesday).to).isEqualTo(Instant.parse("2024-06-13T00:00:00Z"))
        }
    }

    @Test
    fun `a symbol outside the run resolves on first use`() {
        val cal = calendar("BTCUSD")
        assertThat(cal.forSymbol("SPX")).isEqualTo(TradingCalendar.nyse())
        assertThat(cal.runSymbols).containsExactly("BTCUSD")
    }

    @Test
    fun `anchors follow the first symbol`() {
        val t = Instant.parse("2024-06-12T12:00:00Z")
        assertThat(calendar("EURUSD", "BTCUSD").anchorEpochFor(SessionAnchor.PreviousDay, t))
            .isEqualTo(TradingCalendar.fxDefault().anchorEpochFor(SessionAnchor.PreviousDay, t))
        assertThat(calendar("BTCUSD", "EURUSD").name).isEqualTo("crypto")
    }

    @Test
    fun `its own count waits for every run symbol and ignores stream order`() {
        val fxHourly = TradingCalendar.fxDefault().tradingPeriodsPerYear(TimeWindow.ONE_HOUR)
        assertThat(
            calendar("BTCUSD", "EURUSD").tradingPeriodsPerYear(TimeWindow.ONE_HOUR),
        ).isEqualByComparingTo(fxHourly)
        assertThat(
            calendar("EURUSD", "BTCUSD").tradingPeriodsPerYear(TimeWindow.ONE_HOUR),
        ).isEqualByComparingTo(fxHourly)
        assertThat(calendar("BTCUSD").tradingPeriodsPerYear(TimeWindow.ONE_HOUR)).isEqualByComparingTo("8766")
    }

    @Test
    fun `a sampler over any symbol counts the union`() {
        val cal = calendar("EURUSD", "BTCUSD")
        assertThat(
            cal.periodsPerYear(cal.runSymbols, TimeWindow.ONE_HOUR, SessionOverlap.ANY),
        ).isEqualByComparingTo("8766")
    }
}
