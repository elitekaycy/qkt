package com.qkt.app

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
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
            ctx: SessionContext,
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
            ctx: SessionContext,
            emit: (Signal) -> Unit,
        ) {
            when (step++) {
                0 -> emit(Signal.Buy(symbol, Money.of(size)))
                1 -> emit(Signal.Sell(symbol, Money.of(size)))
            }
        }
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
        assertThat(result.realizedTotal).isEqualByComparingTo(Money.ZERO)
        assertThat(result.unrealizedTotal).isEqualByComparingTo(Money.ZERO)
        assertThat(result.totalPnL).isEqualByComparingTo(Money.ZERO)
        assertThat(result.tradeCount).isEqualTo(0)
        assertThat(result.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(result.maxDrawdown).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `single buy produces one trade and zero realized`() {
        val result =
            Backtest(
                strategies = listOf(buyEveryTickStrategy("XAUUSD", "1")),
                ticks = listOf(tick("XAUUSD", "100", 1L)),
            ).run()

        assertThat(result.trades).hasSize(1)
        assertThat(result.trades[0].realized).isEqualByComparingTo(Money.ZERO)
        assertThat(result.tradeCount).isEqualTo(1)
        assertThat(result.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(result.finalPositions["XAUUSD"]?.quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `buy then sell produces realized PnL and increments win rate`() {
        val result =
            Backtest(
                strategies = listOf(buyThenSellStrategy("XAUUSD", "1")),
                ticks =
                    listOf(
                        tick("XAUUSD", "100", 1L),
                        tick("XAUUSD", "110", 2L),
                    ),
            ).run()

        assertThat(result.trades).hasSize(2)
        assertThat(result.trades[0].realized).isEqualByComparingTo(Money.ZERO)
        assertThat(result.trades[1].realized).isEqualByComparingTo(Money.of("10"))
        assertThat(result.tradeCount).isEqualTo(2)
        assertThat(result.winRate).isEqualByComparingTo(Money.of("1"))
        assertThat(result.realizedTotal).isEqualByComparingTo(Money.of("10"))
        assertThat(result.finalPositions).isEmpty()
    }

    @Test
    fun `risk-rejected order appears in rejections, not trades`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("0.5")))
        val result =
            Backtest(
                strategies = listOf(buyEveryTickStrategy("XAUUSD", "1")),
                rules = rules,
                ticks = listOf(tick("XAUUSD", "100", 1L)),
            ).run()

        assertThat(result.trades).isEmpty()
        assertThat(result.rejections).hasSize(1)
        assertThat(result.rejections[0].request.symbol).isEqualTo("XAUUSD")
        assertThat(result.rejections[0].reason).contains("MaxPositionSize")
        assertThat(result.tradeCount).isEqualTo(0)
    }

    @Test
    fun `mark-to-market drawdown captures unrealized swings on open positions`() {
        val result =
            Backtest(
                strategies =
                    listOf(
                        object : Strategy {
                            private var done = false

                            override fun onTick(
                                t: Tick,
                                ctx: SessionContext,
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
                        tick("XAUUSD", "90", 2L),
                        tick("XAUUSD", "110", 3L),
                    ),
            ).run()

        assertThat(result.maxDrawdown).isEqualByComparingTo(Money.of("10"))
    }

    @Test
    fun `max position size rule rejects subsequent buys after limit reached`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("2")))
        val result =
            Backtest(
                strategies = listOf(buyEveryTickStrategy("XAUUSD", "1")),
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
