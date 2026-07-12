package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionEngineSnapshotTest {
    @Test
    fun `operator snapshots wait for the engine thread to reach a consistent boundary`() {
        val closeFeed = CountDownLatch(1)
        val enteredStrategy = CountDownLatch(1)
        val releaseStrategy = CountDownLatch(1)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    enteredStrategy.countDown()
                    releaseStrategy.await(2, TimeUnit.SECONDS)
                }
            }
        val handle =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = heldOpenSource(closeFeed),
                symbols = listOf("X"),
                clock = FixedClock(0L),
                calendar = TradingCalendar.crypto(),
            ).start()
        assertThat(enteredStrategy.await(1, TimeUnit.SECONDS)).isTrue()

        val pending = CompletableFuture.supplyAsync { handle.pendingStackLayerInfos() }
        val reconcile = CompletableFuture.supplyAsync { requireNotNull(handle.reconcile()) }
        Thread.sleep(100L)
        assertThat(pending.isDone).isFalse()
        assertThat(reconcile.isDone).isFalse()

        releaseStrategy.countDown()
        assertThat(pending.get(1, TimeUnit.SECONDS)).isEmpty()
        assertThat(reconcile.get(1, TimeUnit.SECONDS).clean).isTrue()

        closeFeed.countDown()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
    }

    @Test
    fun `operator snapshot completes when a finite feed stops the engine during enqueue`() {
        val handle =
            LiveSession(
                strategies =
                    listOf(
                        "alpha" to
                            object : Strategy {
                                override fun onTick(
                                    tick: Tick,
                                    ctx: StrategyContext,
                                    emit: (Signal) -> Unit,
                                ) = Unit
                            },
                    ),
                source = finiteSource(),
                symbols = listOf("X"),
                clock = FixedClock(0L),
                calendar = TradingCalendar.crypto(),
            ).start()

        val rows = handle.dailySummaryRows()

        assertThat(rows.map { it.strategyId }).containsExactly("alpha")
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
    }

    private fun heldOpenSource(closeFeed: CountDownLatch): MarketSource =
        object : MarketSource {
            override val name = "held-open"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    private var first = true

                    override fun next(): Tick? {
                        if (first) {
                            first = false
                            return Tick("X", Money.of("100"), 1_000L)
                        }
                        closeFeed.await(2, TimeUnit.SECONDS)
                        return null
                    }

                    override fun close() {
                        closeFeed.countDown()
                    }
                }
        }

    private fun finiteSource(): MarketSource =
        object : MarketSource {
            override val name = "finite"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    override fun next(): Tick? = null

                    override fun close() = Unit
                }
        }
}
