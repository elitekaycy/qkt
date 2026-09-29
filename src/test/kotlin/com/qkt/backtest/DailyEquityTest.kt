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

        val rows = acc.result()
        assertThat(rows).hasSize(2)
        assertThat(rows[0].date).isEqualTo(LocalDate.parse("2024-01-02"))
        assertThat(rows[0].open).isEqualByComparingTo("100")
        assertThat(rows[0].high).isEqualByComparingTo("120")
        assertThat(rows[0].low).isEqualByComparingTo("90")
        assertThat(rows[0].close).isEqualByComparingTo("110")
        assertThat(rows[1].open).isEqualByComparingTo("111")
        assertThat(rows[1].close).isEqualByComparingTo("111")
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
}
