package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.positions.StrategyPositionTracker.LegAction
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** One outcome per position: entry costs included, partial fills aggregated (#1452). */
class TradeOutcomesTest {
    private fun fill(
        realized: String,
        opens: Boolean,
        legId: String? = null,
        symbol: String = "GOOG",
        qty: String = "1",
        at: Long = 0L,
        legAction: LegAction? = null,
    ) = TradeRecord(
        trade = Trade("o", symbol, Money.of("100"), Money.of(qty), Side.BUY, at),
        realized = BigDecimal(realized),
        strategyId = "s1",
        reducedExposure = !opens,
        legId = legId,
        legAction = legAction,
    )

    private fun pnls(trades: List<TradeRecord>) = tradeOutcomes(trades).map { it.pnl.toPlainString() }

    @Test
    fun `a netted reversal folds each opening fill's commission into the trade that closes it`() {
        // Opening fills carry no leg id; the closing fill of the same position does (a reversing SMA cross).
        val outcomes =
            pnls(
                listOf(
                    fill("-69.38", opens = true),
                    fill("43.62", opens = false, legId = "p-1"),
                    fill("-60.00", opens = true),
                    fill("10.00", opens = false, legId = "p-2"),
                ),
            )

        assertThat(outcomes).containsExactly("-25.76", "-50.00")
    }

    @Test
    fun `entries on another symbol never land in this symbol's trade`() {
        val outcomes =
            pnls(
                listOf(
                    fill("-5", opens = true, symbol = "MSFT"),
                    fill("-1", opens = true),
                    fill("20", opens = false),
                    fill("7", opens = false, symbol = "MSFT"),
                ),
            )

        assertThat(outcomes).containsExactly("19", "2")
    }

    @Test
    fun `a leg's own entry still matches its exit`() {
        val outcomes =
            pnls(listOf(fill("-0.21", opens = true, legId = "l1"), fill("0.09", opens = false, legId = "l1")))

        assertThat(outcomes.single()).isEqualTo("-0.12")
    }

    @Test
    fun `a position entered and exited in partial fills is one round trip`() {
        val outcomes =
            tradeOutcomes(
                listOf(
                    fill("-0.035", opens = true, legId = "1", qty = "0.01", legAction = LegAction.OPENED),
                    fill("-0.07", opens = true, legId = "1", qty = "0.02", legAction = LegAction.OPENED),
                    fill("-30.985", opens = false, legId = "1", qty = "0.01", at = 5L, legAction = LegAction.CLOSED),
                    fill("-61.97", opens = false, legId = "1", qty = "0.02", at = 6L, legAction = LegAction.CLOSED),
                ),
            )

        assertThat(outcomes).containsExactly(TradeOutcome(closedAtMs = 6L, pnl = BigDecimal("-93.060")))
    }

    @Test
    fun `a scale-out with two take profits is one trade`() {
        val outcomes =
            pnls(
                listOf(
                    fill("-1", opens = true, qty = "2"),
                    fill("30", opens = false, qty = "1"),
                    fill("-10", opens = false, qty = "1"),
                ),
            )

        assertThat(outcomes).containsExactly("19")
    }

    @Test
    fun `a position still partly open at the end counts once, as of its last exit`() {
        val outcomes =
            tradeOutcomes(
                listOf(
                    fill("-1", opens = true, qty = "2", at = 1L),
                    fill("5", opens = false, qty = "1", at = 4L),
                    fill("-1", opens = true, symbol = "MSFT", at = 7L),
                ),
            )

        assertThat(outcomes).containsExactly(TradeOutcome(closedAtMs = 4L, pnl = BigDecimal("4")))
    }

    @Test
    fun `a netted exit larger than the position closes it and opens the next with the excess`() {
        val outcomes =
            pnls(
                listOf(
                    fill("-1", opens = true, qty = "1"),
                    fill("9", opens = false, qty = "3"),
                    fill("-2", opens = false, qty = "1"),
                    fill("4", opens = false, qty = "1"),
                ),
            )

        assertThat(outcomes).containsExactly("8", "2")
    }

    @Test
    fun `hedged legs are separate round trips even when one leg reduces the net position`() {
        val outcomes =
            pnls(
                listOf(
                    fill("-1", opens = true, legId = "long", legAction = LegAction.OPENED),
                    // Opening the short leg reduces the net position, but it is a new position.
                    fill("-1", opens = false, legId = "short", legAction = LegAction.OPENED),
                    fill("10", opens = true, legId = "long", legAction = LegAction.CLOSED),
                    fill("-4", opens = false, legId = "short", legAction = LegAction.CLOSED),
                ),
            )

        assertThat(outcomes).containsExactly("9", "-5")
    }

    @Test
    fun `a leg-routed stop closing a netted entry belongs to that entry`() {
        val outcomes =
            pnls(
                listOf(
                    fill("-1", opens = true, legAction = LegAction.NETTED),
                    fill("-20", opens = false, legId = "p-7", legAction = LegAction.CLOSED),
                ),
            )

        assertThat(outcomes).containsExactly("-21")
    }
}
