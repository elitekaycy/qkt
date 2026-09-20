package com.qkt.backtest

import com.qkt.backtest.BacktestTestStrategies.buyEveryTickStrategy
import com.qkt.backtest.BacktestTestStrategies.cyclingStrategy
import com.qkt.backtest.BacktestTestStrategies.tick
import com.qkt.common.Money
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestRiskGateTest {
    @Test
    fun `halt rules stop the backtest's trading like live`() {
        // Buys at 100, sells at 90 — every round trip realizes -10 on the same day.
        val ticks =
            (0 until 40).map { i ->
                tick("XAUUSD", if (i % 2 == 0) "100" else "90", (i + 1) * 1_000L)
            }
        val strategies = { listOf("loser" to cyclingStrategy("XAUUSD")) }

        val unbounded = Backtest(strategies = strategies(), ticks = ticks).run()

        val halted =
            Backtest(
                strategies = strategies(),
                haltRules =
                    com.qkt.risk.HaltRules
                        .standard(maxDailyLoss = Money.of("25")),
                ticks = ticks,
            ).run()

        assertThat(halted.halts).isNotEmpty
        assertThat(halted.halts.first().reason).contains("daily")
        // After the halt, entries are vetoed — the run trades less than the unbounded one.
        assertThat(halted.trades.size).isLessThan(unbounded.trades.size)
        assertThat(unbounded.halts).isEmpty()
    }

    @Test
    fun `risk-rejected order appears in rejections, not trades`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("0.5")))
        val result =
            Backtest(
                strategies = listOf("test" to buyEveryTickStrategy("XAUUSD", "1")),
                rules = rules,
                ticks = listOf(tick("XAUUSD", "100", 1L)),
            ).run()

        assertThat(result.trades).isEmpty()
        assertThat(result.rejections).hasSize(1)
        assertThat(result.rejections[0].request.symbol).isEqualTo("XAUUSD")
        assertThat(result.rejections[0].reason).contains("MaxPositionSize")
        assertThat(result.global.tradeCount).isEqualTo(0)
    }

    @Test
    fun `max position size rule rejects subsequent buys after limit reached`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("2")))
        val result =
            Backtest(
                strategies = listOf("test" to buyEveryTickStrategy("XAUUSD", "1")),
                rules = rules,
                ticks =
                    listOf(
                        tick("XAUUSD", "100", 1L),
                        tick("XAUUSD", "100", 2L),
                        tick("XAUUSD", "100", 3L),
                        tick("XAUUSD", "100", 4L),
                        tick("XAUUSD", "100", 5L),
                    ),
            ).run()

        assertThat(result.trades).hasSize(2)
        assertThat(result.rejections).hasSize(3)
        assertThat(result.finalPositions["XAUUSD"]?.quantity).isEqualByComparingTo(Money.of("2"))
    }
}
