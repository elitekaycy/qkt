package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingPeriodsTest {
    private val fx = TradingCalendar.fxDefault()
    private val crypto = TradingCalendar.crypto()
    private val nyse = TradingCalendar.nyse()
    private val cme = TradingCalendar.named("cme_globex")!!
    private val hour = TimeWindow.ONE_HOUR

    private fun periods(
        overlap: SessionOverlap,
        vararg pairs: Pair<String, TradingCalendar>,
        window: TimeWindow = hour,
    ) = TradingPeriods.perYear(mapOf(*pairs), window, overlap)

    @Test
    fun `one calendar answers with its own count however many symbols share it`() {
        assertThat(periods(SessionOverlap.ALL, "EURUSD" to fx, "GBPUSD" to fx))
            .isEqualByComparingTo(fx.tradingPeriodsPerYear(hour))
    }

    @Test
    fun `fx and crypto overlap on the fx week and union on the crypto week`() {
        assertThat(
            periods(SessionOverlap.ALL, "EURUSD" to fx, "BTCUSD" to crypto),
        ).isEqualByComparingTo(fx.tradingPeriodsPerYear(hour))
        assertThat(periods(SessionOverlap.ANY, "EURUSD" to fx, "BTCUSD" to crypto)).isEqualByComparingTo("8766")
    }

    @Test
    fun `stream order does not change the count`() {
        assertThat(periods(SessionOverlap.ALL, "BTCUSD" to crypto, "EURUSD" to fx))
            .isEqualByComparingTo(periods(SessionOverlap.ALL, "EURUSD" to fx, "BTCUSD" to crypto))
    }

    @Test
    fun `nyse sits inside the fx week and inside cme globex`() {
        assertThat(
            periods(SessionOverlap.ALL, "SPX" to nyse, "EURUSD" to fx),
        ).isEqualByComparingTo(nyse.tradingPeriodsPerYear(hour))
        assertThat(
            periods(SessionOverlap.ANY, "SPX" to nyse, "EURUSD" to fx),
        ).isEqualByComparingTo(fx.tradingPeriodsPerYear(hour))
        assertThat(
            periods(SessionOverlap.ALL, "SPX" to nyse, "ES" to cme),
        ).isEqualByComparingTo(nyse.tradingPeriodsPerYear(hour))
    }

    @Test
    fun `cme globex sits inside the fx week`() {
        assertThat(
            periods(SessionOverlap.ALL, "ES" to cme, "EURUSD" to fx),
        ).isEqualByComparingTo(cme.tradingPeriodsPerYear(hour))
        assertThat(
            periods(SessionOverlap.ANY, "ES" to cme, "EURUSD" to fx),
        ).isEqualByComparingTo(fx.tradingPeriodsPerYear(hour))
    }

    @Test
    fun `a daily pause does not change the session so venue presets keep the base count`() {
        val ny = ZoneId.of("America/New_York")
        val gold = DailyBreakCalendar(fx, LocalTime.of(16, 45), LocalTime.of(18, 6), ny)
        val btc = DailyBreakCalendar(crypto, LocalTime.of(16, 50), LocalTime.of(17, 10), ny)
        assertThat(
            periods(SessionOverlap.ANY, "XAUUSD" to gold, "BTCUSD" to btc, "EURUSD" to fx),
        ).isEqualByComparingTo("8766")
        assertThat(
            periods(SessionOverlap.ALL, "XAUUSD" to gold, "BTCUSD" to btc),
        ).isEqualByComparingTo(fx.tradingPeriodsPerYear(hour))
    }

    @Test
    fun `sessions that do not nest are measured on the candle grid`() {
        val weekends = weekendsOnly()
        val union = periods(SessionOverlap.ANY, "SPX" to nyse, "W" to weekends, window = TimeWindow.ONE_DAY)
        // NYSE never trades at weekends, so the union's daily bars are the trading days plus the weekend days.
        assertThat(union.multiply(BigDecimal(4))).isEqualByComparingTo(BigDecimal(1005 + weekendDays()))
    }

    @Test
    fun `an empty overlap falls back to the smallest member count`() {
        val weekends = weekendsOnly()
        assertThat(periods(SessionOverlap.ALL, "SPX" to nyse, "W" to weekends, window = TimeWindow.ONE_DAY))
            .isEqualByComparingTo(minOf(nyse.tradingPeriodsPerYear(TimeWindow.ONE_DAY), BigDecimal("104")))
    }

    @Test
    fun `no symbols is refused`() {
        assertThatThrownBy { TradingPeriods.perYear(emptyMap(), hour, SessionOverlap.ALL) }
            .hasMessageContaining("at least one symbol")
    }

    private fun weekendDays(): Int {
        var d = TradingPeriods.REFERENCE_FROM
        var n = 0
        while (d.isBefore(TradingPeriods.REFERENCE_TO)) {
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) n++
            d = d.plusDays(1)
        }
        return n
    }

    private fun weekendsOnly(): TradingCalendar =
        object : TradingCalendar by crypto {
            override val name = "weekends"

            override fun isInSession(
                symbol: String,
                t: Instant,
            ) = t.atZone(ZoneOffset.UTC).dayOfWeek.value >= 6

            override fun tradingPeriodsPerYear(window: TimeWindow) = BigDecimal("104")
        }
}
