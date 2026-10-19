package com.qkt.backtest

import com.qkt.common.Money
import java.time.LocalDate
import java.time.YearMonth
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyEquityTest {
    private val day = 86_400_000L

    private fun ms(
        date: String,
        hour: Int = 0,
    ) = LocalDate.parse(date).toEpochDay() * day + hour * 3_600_000L

    @Test
    fun `folds samples into one open-high-low-close row per UTC day`() {
        val acc = DailyEquityAccumulator()
        acc.accept(ms("2024-01-02", 1), Money.of("100"))
        acc.accept(ms("2024-01-02", 5), Money.of("120"))
        acc.accept(ms("2024-01-02", 9), Money.of("90"))
        acc.accept(ms("2024-01-02", 23), Money.of("110"))
        acc.accept(ms("2024-01-03", 0), Money.of("111"))
        acc.accept(ms("2024-01-03", 4), Money.of("111"))

        val rows = acc.result()
        assertThat(rows).hasSize(2)
        assertThat(rows[0].date).isEqualTo(LocalDate.parse("2024-01-02"))
        assertThat(rows[0].open).isEqualByComparingTo("100")
        assertThat(rows[0].high).isEqualByComparingTo("120")
        assertThat(rows[0].low).isEqualByComparingTo("90")
        // The 2024-01-03 00:00 sample is taken before the tick that crosses midnight: 2024-01-02's close.
        assertThat(rows[0].close).isEqualByComparingTo("111")
        assertThat(rows[1].open).isEqualByComparingTo("111")
        assertThat(rows[1].close).isEqualByComparingTo("111")
    }

    @Test
    fun `a midnight sample after a gap of days opens its own day`() {
        val acc = DailyEquityAccumulator()
        acc.accept(ms("2024-01-05", 21), Money.of("100"))
        acc.accept(ms("2024-01-08", 0), Money.of("101"))
        acc.accept(ms("2024-01-08", 1), Money.of("102"))

        val rows = acc.result()
        assertThat(rows.map { it.date.toString() }).containsExactly("2024-01-05", "2024-01-08")
        assertThat(rows[0].close).isEqualByComparingTo("100")
        assertThat(rows[1].open).isEqualByComparingTo("101")
    }

    @Test
    fun `monthly returns compound to the total return`() {
        val rows =
            listOf(
                DailyEquity(
                    LocalDate.parse("2024-01-02"),
                    Money.of("100"),
                    Money.of("100"),
                    Money.of("100"),
                    Money.of("100"),
                ),
                DailyEquity(
                    LocalDate.parse("2024-01-31"),
                    Money.of("100"),
                    Money.of("110"),
                    Money.of("100"),
                    Money.of("110"),
                ),
                DailyEquity(
                    LocalDate.parse("2024-02-15"),
                    Money.of("110"),
                    Money.of("110"),
                    Money.of("99"),
                    Money.of("99"),
                ),
                DailyEquity(
                    LocalDate.parse("2024-03-01"),
                    Money.of("99"),
                    Money.of("120"),
                    Money.of("99"),
                    Money.of("120"),
                ),
            )

        val monthly = monthlyReturns(rows)

        assertThat(monthly.map { it.month }).containsExactly(
            YearMonth.of(2024, 1),
            YearMonth.of(2024, 2),
            YearMonth.of(2024, 3),
        )
        assertThat(monthly[0].value).isEqualByComparingTo("0.1")
        assertThat(monthly[1].value).isEqualByComparingTo("-0.1")
        val compounded =
            monthly.fold(
                java.math.BigDecimal.ONE,
            ) { acc, r ->
                acc.multiply(
                    java.math.BigDecimal.ONE
                        .add(r.value),
                )
            }
        assertThat(compounded).isCloseTo(
            Money.of("1.2"),
            org.assertj.core.data.Offset
                .offset(Money.of("0.00000001")),
        )
    }

    @Test
    fun `an empty series has no rows and no months`() {
        assertThat(DailyEquityAccumulator().result()).isEmpty()
        assertThat(monthlyReturns(emptyList())).isEmpty()
    }

    @Test
    fun `a run ending at midnight closes its last day there instead of reporting a day it did not cover`() {
        // --to 2024-01-04: the last 15m bar closes at 2024-01-04T00:00, sampled before that instant's tick.
        val acc = DailyEquityAccumulator()
        acc.accept(ms("2024-01-02", 1), Money.of("100"))
        acc.accept(ms("2024-01-03", 12), Money.of("105"))
        acc.accept(ms("2024-01-04", 0), Money.of("108"))

        val rows = acc.result()
        assertThat(rows.map { it.date.toString() }).containsExactly("2024-01-02", "2024-01-03")
        assertThat(rows.last().close).isEqualByComparingTo("108")
        assertThat(rows.last().high).isEqualByComparingTo("108")
    }

    @Test
    fun `a lone midnight sample after a gap still opens its own day`() {
        val acc = DailyEquityAccumulator()
        acc.accept(ms("2024-01-05", 20), Money.of("100"))
        acc.accept(ms("2024-01-08", 0), Money.of("101"))

        assertThat(acc.result().map { it.date.toString() }).containsExactly("2024-01-05", "2024-01-08")
    }

    @Test
    fun `daily bars sample only at midnight and still report every day`() {
        // Each sample closes the day before it: 01-02 00:00 closes 01-01, 01-03 00:00 closes 01-02, and so on.
        val acc = DailyEquityAccumulator()
        acc.accept(ms("2024-01-01"), Money.of("100"))
        acc.accept(ms("2024-01-02"), Money.of("103"))
        acc.accept(ms("2024-01-03"), Money.of("97"))
        acc.accept(ms("2024-01-04"), Money.of("99"))

        val rows = acc.result()
        assertThat(rows.map { it.date.toString() }).containsExactly("2024-01-01", "2024-01-02", "2024-01-03")
        assertThat(rows.map { it.open.toPlainString() }).containsExactly("100.00000000", "103.00000000", "97.00000000")
        assertThat(rows.map { it.close.toPlainString() }).containsExactly("103.00000000", "97.00000000", "99.00000000")
        assertThat(rows[1].low).isEqualByComparingTo("97")
        assertThat(rows[1].high).isEqualByComparingTo("103")
    }

    @Test
    fun `a day that a midnight sample began opens at that sample`() {
        val acc = DailyEquityAccumulator()
        acc.accept(ms("2024-01-02", 1), Money.of("100"))
        acc.accept(ms("2024-01-03", 0), Money.of("110"))
        acc.accept(ms("2024-01-03", 1), Money.of("99"))

        val rows = acc.result()
        assertThat(rows[1].open).isEqualByComparingTo("110")
        assertThat(rows[1].high).isEqualByComparingTo("110")
        assertThat(rows[1].low).isEqualByComparingTo("99")
        assertThat(acc.maxDailyDrawdown()).isEqualByComparingTo("0.1")
    }
}
