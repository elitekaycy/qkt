package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * FX and CME Globex count the bars their sessions really form (#1491): windows that divide an hour keep the open-time
 * share exactly as before, and longer windows count the bars the open, close and halt cut short.
 */
class WeeklySessionPeriodsTest {
    private val fx = TradingCalendar.fxDefault()
    private val cme = TradingCalendar.named("cme_globex")!!

    private fun scanned(
        calendar: TradingCalendar,
        window: TimeWindow,
    ): BigDecimal {
        val from =
            TradingPeriods.REFERENCE_FROM
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val to =
            TradingPeriods.REFERENCE_TO
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val bars = HashSet<Long>()
        var t = from
        while (t < to) {
            if (calendar.isInSession("X", Instant.ofEpochMilli(t))) bars.add(Math.floorDiv(t, window.durationMs))
            t += 60_000L
        }
        return BigDecimal(bars.size).divide(BigDecimal(4))
    }

    private fun formula(
        open: Int,
        week: Int,
        window: TimeWindow,
    ): BigDecimal =
        BigDecimal("525960")
            .multiply(BigDecimal(open))
            .divide(BigDecimal(week), Money.CONTEXT)
            .divide(BigDecimal(window.durationMs).divide(BigDecimal("60000"), Money.CONTEXT), Money.CONTEXT)

    @Test
    fun `windows that divide an hour keep the open-time share digit for digit`() {
        for (spec in listOf("1m", "5m", "15m", "30m", "1h")) {
            val w = TimeWindow.parse(spec)
            assertThat(fx.tradingPeriodsPerYear(w)).describedAs("fx $spec").isEqualTo(formula(5, 7, w))
            assertThat(cme.tradingPeriodsPerYear(w)).describedAs("cme $spec").isEqualTo(formula(115, 168, w))
        }
        assertThat(fx.tradingPeriodsPerYear(TimeWindow.ONE_HOUR)).isEqualByComparingTo("6261.428571428572")
    }

    @Test
    fun `longer windows count every bar the session touches`() {
        for (spec in listOf("2h", "3h", "4h", "6h", "12h", "1d")) {
            val w = TimeWindow.parse(spec)
            assertThat(fx.tradingPeriodsPerYear(w)).describedAs("fx $spec").isEqualByComparingTo(scanned(fx, w))
            assertThat(cme.tradingPeriodsPerYear(w)).describedAs("cme $spec").isEqualByComparingTo(scanned(cme, w))
        }
    }

    @Test
    fun `a daily fx series forms six utc bars a week`() {
        assertThat(fx.tradingPeriodsPerYear(TimeWindow.ONE_DAY)).isEqualByComparingTo("313")
        assertThat(fx.tradingPeriodsPerYear(TimeWindow.parse("4h"))).isEqualByComparingTo("1616.75")
    }
}
