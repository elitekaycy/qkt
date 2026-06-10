package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestTest {
    private fun tick(
        symbol: String,
        price: String,
        ts: Long,
    ) = Tick(symbol, Money.of(price), ts)

    private fun buyEveryTickStrategy(
        symbol: String,
        size: String,
    ) = object : Strategy {
        override fun onTick(
            t: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            emit(Signal.Buy(symbol, Money.of(size)))
        }
    }

    private fun buyThenSellStrategy(
        symbol: String,
        size: String,
    ) = object : Strategy {
        private var step = 0

        override fun onTick(
            t: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            when (step++) {
                0 -> emit(Signal.Buy(symbol, Money.of(size)))
                1 -> emit(Signal.Sell(symbol, Money.of(size)))
            }
        }
    }

    private fun cyclingStrategy(symbol: String) =
        object : Strategy {
            private var long = false

            override fun onTick(
                t: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!long) {
                    emit(Signal.Buy(symbol, Money.of("1")))
                } else {
                    emit(Signal.Sell(symbol, Money.of("1")))
                }
                long = !long
            }
        }

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
