package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Each exit's outcome includes the cost of the entry it closes, whether or not the entry fill names a leg. */
class TradeOutcomesTest {
    private fun fill(
        realized: String,
        opens: Boolean,
        legId: String? = null,
        symbol: String = "GOOG",
    ) = TradeRecord(
        trade = Trade("o", symbol, Money.of("100"), Money.of("1"), Side.BUY, 0L),
        realized = BigDecimal(realized),
        strategyId = "s1",
        reducedExposure = !opens,
        legId = legId,
    )

    @Test
    fun `a netted reversal folds each opening fill's commission into the trade that closes it`() {
        // Opening fills carry no leg id; the closing fill of the same position does (a reversing SMA cross).
        val outcomes =
            tradeOutcomes(
                listOf(
                    fill("-69.38", opens = true),
                    fill("43.62", opens = false, legId = "p-1"),
                    fill("-60.00", opens = true),
                    fill("10.00", opens = false, legId = "p-2"),
                ),
            )

        assertThat(outcomes.map { it.toPlainString() }).containsExactly("-25.76", "-50.00")
    }

    @Test
    fun `entries on another symbol never land in this symbol's trade`() {
        val outcomes =
            tradeOutcomes(
                listOf(
                    fill("-5", opens = true, symbol = "MSFT"),
                    fill("-1", opens = true),
                    fill("20", opens = false),
                    fill("7", opens = false, symbol = "MSFT"),
                ),
            )

        assertThat(outcomes.map { it.toPlainString() }).containsExactly("19", "2")
    }

    @Test
    fun `a leg's own entry still matches its exit`() {
        val outcomes =
            tradeOutcomes(listOf(fill("-0.21", opens = true, legId = "l1"), fill("0.09", opens = false, legId = "l1")))

        assertThat(outcomes.single().toPlainString()).isEqualTo("-0.12")
    }
}
