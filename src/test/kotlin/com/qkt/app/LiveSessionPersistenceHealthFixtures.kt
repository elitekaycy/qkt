package com.qkt.app

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.CountDownLatch

object LiveSessionPersistenceHealthFixtures {
    fun noOpStrategy(): Strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5L)
        return condition()
    }

    fun heldOpenSource(closeFeed: CountDownLatch): MarketSource =
        object : MarketSource {
            override val name = "held-open"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    override fun next(): Tick? {
                        closeFeed.await()
                        return null
                    }

                    override fun close() {
                        closeFeed.countDown()
                    }
                }
        }
}
