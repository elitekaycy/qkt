package com.qkt.parity

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** A market source over a hand-fed tick feed, one per portfolio child and one for the supervisor. */
internal object BookRiskRaceFeeds {
    class ManualSource(
        private val feed: ManualFeed,
    ) : MarketSource {
        override val name = "portfolio-book-risk-race"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    class ManualFeed : TickFeed {
        private val queue = LinkedBlockingQueue<Tick>()
        private val readCalls = AtomicInteger()

        fun offer(tick: Tick) {
            queue.put(tick)
        }

        fun awaitReadCalls(
            expected: Int,
            timeoutMs: Long = 2_000L,
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (readCalls.get() < expected && System.currentTimeMillis() < deadline) Thread.sleep(5L)
            return readCalls.get() >= expected
        }

        override fun next(): Tick? {
            readCalls.incrementAndGet()
            val tick = queue.take()
            return tick.takeUnless { it.symbol == CLOSE_SYMBOL }
        }

        override fun close() {
            queue.offer(Tick(CLOSE_SYMBOL, BigDecimal.ONE, Long.MIN_VALUE))
        }

        private companion object {
            const val CLOSE_SYMBOL = "__CLOSE__"
        }
    }
}
