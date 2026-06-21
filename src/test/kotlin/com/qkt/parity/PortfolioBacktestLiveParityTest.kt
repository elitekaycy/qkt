package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Portfolio analog of [BacktestLiveParityTest]: a book of N children co-simulated on one shared
 * engine must produce identical trades — attributed to the same child — in `Backtest` and
 * `LiveSession` given the same ticks.
 *
 * This is the parity guarantee behind `qkt backtest <portfolio>`, which runs the children as N
 * attributed strategies on one shared engine ([com.qkt.cli.BacktestContext.buildPortfolio]): the
 * per-child execution and attribution the backtest report shows must match what live paper-trading
 * the same children would do. Children here are always-on, matching the backtest path (which does
 * not yet apply WHEN..RUN regime gates), so both paths run every child on every tick.
 */
class PortfolioBacktestLiveParityTest {
    private val symA = "BTCUSDT"
    private val symB = "ETHUSDT"
    private val initialTs = 1_700_000_000_000L

    // Interleaved BTC + ETH ticks (ETH offset +1ms so no same-timestamp cross-symbol ambiguity).
    // Each child trades only its own symbol, so per-child trade streams are independent and the
    // co-simulation must keep them attributed correctly.
    private fun ticks(): List<Tick> {
        val out = mutableListOf<Tick>()
        for (i in 0 until 60) {
            val a = 10000 + (i / 12) * 100 + (if (i % 12 < 6) i % 12 else 12 - i % 12) * 10
            val b = 2000 + (i / 8) * 50 + (if (i % 8 < 4) i % 8 else 8 - i % 8) * 5
            out.add(Tick(symA, Money.of(a.toString()), initialTs + i * 60_000L))
            out.add(Tick(symB, Money.of(b.toString()), initialTs + i * 60_000L + 1L))
        }
        return out
    }

    /** A child that buys/sells a fixed quantity of its own symbol on a fixed cycle of its own ticks. */
    private fun childOn(
        symbol: String,
        qty: String,
        buyMod: Int,
        sellMod: Int,
        period: Int,
    ): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (tick.symbol != symbol) return
                val s = step++
                when {
                    s % period == buyMod -> emit(Signal.Buy(symbol, Money.of(qty)))
                    s % period == sellMod -> emit(Signal.Sell(symbol, Money.of(qty)))
                }
            }
        }

    // Distinct quantities per child so a mis-attributed trade is caught by more than its id.
    private fun children(): List<Pair<String, Strategy>> =
        listOf(
            "book:a" to childOn(symA, qty = "0.1", buyMod = 0, sellMod = 6, period = 12),
            "book:b" to childOn(symB, qty = "0.2", buyMod = 0, sellMod = 4, period = 8),
        )

    private class TickListFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            return if (i >= ticks.size) null else ticks[i]
        }

        override fun close() = Unit
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "PortfolioParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = TickListFeed(ticks)
    }

    @Test
    fun `portfolio backtest and live-paper produce identical per-child trades`() {
        val tickSeq = ticks()
        val startingBalance = BigDecimal("10000")

        val backtestResult =
            Backtest(
                strategies = children(),
                ticks = tickSeq,
                initialTimestamp = tickSeq.first().timestamp,
                startingBalance = startingBalance,
            ).run()

        val liveTrades = mutableListOf<Pair<String, Trade>>()
        val liveClock = FixedClock(time = tickSeq.first().timestamp)
        val session =
            LiveSession(
                strategies = children(),
                source = FakeSource(tickSeq),
                symbols = listOf(symA, symB),
                clock = liveClock,
                onTrade = { trade, _, strategyId -> liveTrades.add(strategyId to trade) },
            ).start()
        check(session.awaitTermination(Duration.ofSeconds(10))) { "live session did not terminate" }

        val backtestTrades = backtestResult.trades.map { it.strategyId to it.trade }

        assertThat(backtestTrades).isNotEmpty
        // Both children must trade — a real multi-strategy book, not a degenerate single-child case.
        assertThat(backtestTrades.map { it.first }.toSet())
            .containsExactlyInAnyOrder("book:a", "book:b")
        assertThat(liveTrades).hasSize(backtestTrades.size)
        for (i in backtestTrades.indices) {
            val (bid, b) = backtestTrades[i]
            val (lid, l) = liveTrades[i]
            assertThat(lid).isEqualTo(bid)
            assertThat(l.symbol).isEqualTo(b.symbol)
            assertThat(l.side).isEqualTo(b.side)
            assertThat(l.quantity).isEqualByComparingTo(b.quantity)
            assertThat(l.price).isEqualByComparingTo(b.price)
            assertThat(l.timestamp).isEqualTo(b.timestamp)
            assertThat(l.orderId).isEqualTo(b.orderId)
        }
    }
}
