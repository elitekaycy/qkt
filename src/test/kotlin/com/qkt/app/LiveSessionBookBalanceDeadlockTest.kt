package com.qkt.app

import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.cli.daemon.portfolio.PortfolioBookBalance
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Two portfolio children sizing `OF BOOK` on the same tick (#1160). Sizing runs on each child's
 * engine thread and reads every sibling's realized PnL; when that read queued a query on the
 * sibling's engine thread, each child waited for the other and both halted at the snapshot timeout.
 */
class LiveSessionBookBalanceDeadlockTest {
    @Test
    fun `the book balance is readable while a child's engine thread is busy`() {
        val engineBusy = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeFeed = CountDownLatch(1)
        val child =
            LiveSession(
                strategies = listOf("b" to busyOnFirstTick(engineBusy, release)),
                source = oneTickThenHeldOpen(closeFeed),
                symbols = listOf("EXNESS:X"),
                clock = FixedClock(0L),
                calendar = TradingCalendar.crypto(),
                brokerFactories = mapOf("exness" to paper),
                initialBalance = BigDecimal("10000"),
            ).start()
        val book = PortfolioBookBalance(BigDecimal("50000")).also { it.bind(listOf({ child.realizedPnl("b") })) }
        val pool = Executors.newFixedThreadPool(2)

        try {
            assertThat(engineBusy.await(5, TimeUnit.SECONDS)).isTrue()
            // The sibling's sizing call: answered at once, although the engine thread is busy.
            assertThat(
                pool.submit<BigDecimal> { book.balance() }.get(1, TimeUnit.SECONDS),
            ).isEqualByComparingTo("50000")
            // The read the book used before waits for that engine thread, which is how two
            // children sizing on one tick came to wait for each other.
            val blocking = pool.submit<BigDecimal> { child.pnlSnapshot("b").realized }
            assertThatThrownBy { blocking.get(300, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            release.countDown()
            assertThat(blocking.get(5, TimeUnit.SECONDS)).isEqualByComparingTo("0")
        } finally {
            release.countDown()
            closeFeed.countDown()
            pool.shutdownNow()
            child.stop()
        }
    }

    private val paper: BrokerFactory = { bus, clock, prices, _, _ -> PaperBroker(bus, clock, prices) }

    private fun oneTickThenHeldOpen(closeFeed: CountDownLatch): MarketSource =
        object : MarketSource {
            override val name = "one-tick"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    private var first = true

                    override fun next(): Tick? {
                        if (first) {
                            first = false
                            return Tick("EXNESS:X", Money.of("100"), 1_000L)
                        }
                        closeFeed.await(10, TimeUnit.SECONDS)
                        return null
                    }

                    override fun close() = closeFeed.countDown()
                }
        }

    /** Holds the engine thread inside `onTick`, as a child does while it sizes an entry. */
    private fun busyOnFirstTick(
        engineBusy: CountDownLatch,
        release: CountDownLatch,
    ): DslCompiledStrategy =
        object : DslCompiledStrategy {
            override val declaredStreams = mapOf("x" to HubKey("EXNESS", "X", "1m"))
            override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
            override val retentionByKey: Map<HubKey, Int> = emptyMap()
            override val pendingStacks = PendingStacks()

            override fun bindToHub(
                hub: CandleHub,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                engineBusy.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
}
