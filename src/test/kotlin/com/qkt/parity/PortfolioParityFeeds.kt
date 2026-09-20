package com.qkt.parity

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Market sources whose tick feeds the portfolio deployer parity tests release or push by hand. */
internal object PortfolioParityFeeds {
    class HeldSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        private val released = CountDownLatch(1)

        override val name = "portfolio-parity"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = HeldFeed(ticks, released)

        fun release() {
            released.countDown()
        }
    }

    class HeldFeed(
        private val ticks: List<Tick>,
        private val released: CountDownLatch,
    ) : TickFeed {
        private val index = AtomicInteger()
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            while (released.count > 0L && closed.count > 0L) {
                released.await(10L, TimeUnit.MILLISECONDS)
            }
            if (closed.count == 0L) return null
            val next = index.getAndIncrement()
            if (next < ticks.size) return ticks[next]
            closed.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            closed.countDown()
        }
    }

    class ManualSource(
        private val feed: ManualFeed,
    ) : MarketSource {
        override val name = "portfolio-aggregate-parity"
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
            while (readCalls.get() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L)
            }
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
