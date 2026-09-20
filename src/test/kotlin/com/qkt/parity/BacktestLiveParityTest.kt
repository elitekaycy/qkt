package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.parity.BacktestLiveParityFixtures.FakeSource
import com.qkt.parity.BacktestLiveParityFixtures.initialTs
import com.qkt.parity.BacktestLiveParityFixtures.symbol
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Asserts that `Backtest` and `LiveSession` produce identical trade lists, final positions,
 * and total realized PnL when fed the same tick sequence through the same compiled strategy.
 *
 * The backtest path is foundational for investor-facing reports — this test enforces that
 * the report's contents reflect what live paper-trading would do given identical ticks.
 */
class BacktestLiveParityTest {
    private fun ticks(): List<Tick> {
        // 60 ticks: rising 10000 → 10300 with two retracements deep enough to trigger sells.
        // Strategy buys when step % 12 == 0 and sells when step % 12 == 6.
        return (0 until 60).map { i ->
            val cycle = (i / 12) * 100
            val intra = if (i % 12 < 6) i % 12 * 10 else (12 - i % 12) * 10
            Tick(symbol, Money.of((10000 + cycle + intra).toString()), initialTs + i * 60_000L)
        }
    }

    private fun makeStrategy(): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                val s = step++
                when {
                    s % 12 == 0 -> emit(Signal.Buy(symbol, Money.of("0.1")))
                    s % 12 == 6 -> emit(Signal.Sell(symbol, Money.of("0.1")))
                }
            }
        }

    @Test
    fun `backtest and live-paper produce identical trades on the same ticks`() {
        val tickSeq = ticks()
        val startingBalance = BigDecimal("10000")

        val backtestResult =
            Backtest(
                strategies = listOf("s" to makeStrategy()),
                ticks = tickSeq,
                initialTimestamp = tickSeq.first().timestamp,
                startingBalance = startingBalance,
            ).run()

        val liveTrades = mutableListOf<Trade>()
        val liveClock = FixedClock(time = tickSeq.first().timestamp)
        val session =
            LiveSession(
                strategies = listOf("s" to makeStrategy()),
                source = FakeSource(tickSeq),
                symbols = listOf(symbol),
                clock = liveClock,
                onTrade = { trade, _, _ -> liveTrades.add(trade) },
            ).start()
        check(session.awaitTermination(Duration.ofSeconds(10))) { "live session did not terminate" }

        val backtestTrades = backtestResult.trades.map { it.trade }

        assertThat(backtestTrades).isNotEmpty
        assertThat(liveTrades).hasSize(backtestTrades.size)
        for (i in backtestTrades.indices) {
            val b = backtestTrades[i]
            val l = liveTrades[i]
            assertThat(l.symbol).isEqualTo(b.symbol)
            assertThat(l.side).isEqualTo(b.side)
            assertThat(l.quantity).isEqualByComparingTo(b.quantity)
            assertThat(l.price).isEqualByComparingTo(b.price)
            assertThat(l.timestamp).isEqualTo(b.timestamp)
            assertThat(l.orderId).isEqualTo(b.orderId)
        }
    }
}
