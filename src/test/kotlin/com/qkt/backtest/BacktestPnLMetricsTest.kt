package com.qkt.backtest

import com.qkt.backtest.BacktestTestStrategies.buyEveryTickStrategy
import com.qkt.backtest.BacktestTestStrategies.buyThenSellStrategy
import com.qkt.backtest.BacktestTestStrategies.tick
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestPnLMetricsTest {
    @Test
    fun `empty ticks produces empty result with zero metrics`() {
        val result =
            Backtest(
                strategies = emptyList(),
                ticks = emptyList(),
            ).run()

        assertThat(result.trades).isEmpty()
        assertThat(result.rejections).isEmpty()
        assertThat(result.finalPositions).isEmpty()
        assertThat(result.global.realizedTotal).isEqualByComparingTo(Money.ZERO)
        assertThat(result.global.unrealizedTotal).isEqualByComparingTo(Money.ZERO)
        assertThat(result.global.totalPnL).isEqualByComparingTo(Money.ZERO)
        assertThat(result.global.tradeCount).isEqualTo(0)
        assertThat(result.global.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(result.global.maxDrawdown).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `single buy produces one trade and zero realized`() {
        val result =
            Backtest(
                strategies = listOf("test" to buyEveryTickStrategy("XAUUSD", "1")),
                ticks = listOf(tick("XAUUSD", "100", 1L)),
            ).run()

        assertThat(result.trades).hasSize(1)
        assertThat(result.trades[0].realized).isEqualByComparingTo(Money.ZERO)
        assertThat(result.global.tradeCount).isEqualTo(1)
        assertThat(result.global.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(result.finalPositions["XAUUSD"]?.quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `buy then sell produces realized PnL and increments win rate`() {
        val result =
            Backtest(
                strategies = listOf("test" to buyThenSellStrategy("XAUUSD", "1")),
                ticks =
                    listOf(
                        tick("XAUUSD", "100", 1L),
                        tick("XAUUSD", "110", 2L),
                    ),
            ).run()

        assertThat(result.trades).hasSize(2)
        assertThat(result.trades[0].realized).isEqualByComparingTo(Money.ZERO)
        assertThat(result.trades[1].realized).isEqualByComparingTo(Money.of("10"))
        assertThat(result.global.tradeCount).isEqualTo(2)
        assertThat(result.global.winRate).isEqualByComparingTo(Money.of("1"))
        assertThat(result.global.realizedTotal).isEqualByComparingTo(Money.of("10"))
        assertThat(result.finalPositions).isEmpty()
    }

    @Test
    fun `mark-to-market drawdown captures unrealized swings on open positions`() {
        val result =
            Backtest(
                strategies =
                    listOf(
                        "test" to
                            object : Strategy {
                                private var done = false

                                override fun onTick(
                                    t: Tick,
                                    ctx: StrategyContext,
                                    emit: (Signal) -> Unit,
                                ) {
                                    if (!done) {
                                        emit(Signal.Buy("XAUUSD", Money.of("1")))
                                        done = true
                                    }
                                }
                            },
                    ),
                ticks =
                    listOf(
                        tick("XAUUSD", "100", 1L),
                        tick("XAUUSD", "120", 2L),
                        tick("XAUUSD", "110", 3L),
                    ),
            ).run()

        // peak unrealized equity = +20 (price 120), then drops to +10 (price 110)
        // fractional drawdown = (20 - 10) / 20 = 0.5
        assertThat(result.global.maxDrawdown).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `raw multi-symbol ticks sample equity once per candle boundary`() {
        val result =
            Backtest(
                strategies = emptyList(),
                ticks =
                    listOf(
                        tick("BACKTEST:A", "100", 0L),
                        tick("BACKTEST:B", "200", 0L),
                        tick("BACKTEST:A", "101", 60_000L),
                        tick("BACKTEST:B", "201", 60_000L),
                        tick("BACKTEST:A", "102", 120_000L),
                        tick("BACKTEST:B", "202", 120_000L),
                    ),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(result.global.equityCurve.map(EquitySample::timestamp))
            .containsExactly(60_000L, 120_000L)
    }
}
