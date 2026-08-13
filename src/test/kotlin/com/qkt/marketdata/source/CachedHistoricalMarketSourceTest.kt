package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CachedHistoricalMarketSourceTest {
    @Test
    fun `identical bar requests reuse cached upstream response`() {
        val delegate = RecordingSource()
        val source = CachedHistoricalMarketSource(delegate)

        val first = source.bars("S0:EURUSD", oneMinute(), range()).toList()
        val second = source.bars("S0:EURUSD", oneMinute(), range()).toList()

        assertThat(second).isEqualTo(first)
        assertThat(delegate.barRequestCount("S0:EURUSD")).isEqualTo(1)
    }

    @Test
    fun `concurrent identical bar requests join one in-flight read`() {
        val delegate = RecordingSource(blockBars = true)
        val source = CachedHistoricalMarketSource(delegate)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<List<Candle>> { source.bars("S0:EURUSD", oneMinute(), range()).toList() }
            assertThat(delegate.awaitBarStarted()).isTrue()

            val second = executor.submit<List<Candle>> { source.bars("S0:EURUSD", oneMinute(), range()).toList() }
            Thread.sleep(100L)
            assertThat(delegate.barRequestCount("S0:EURUSD")).isEqualTo(1)

            delegate.releaseBars()
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(first.get(2, TimeUnit.SECONDS))
            assertThat(delegate.barRequestCount("S0:EURUSD")).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `remapped warmup bar requests share canonical cached response`() {
        val delegate = RecordingSource()
        val cached = CachedHistoricalMarketSource(delegate)
        val remapped = PrefixRemapMarketSource(cached, delegatePrefix = "S0:", localPrefix = "S1:")

        val first = remapped.bars("S1:EURUSD", oneMinute(), range()).toList()
        val second = remapped.bars("S1:EURUSD", oneMinute(), range()).toList()

        assertThat(first.map { it.symbol }).containsExactly("S1:EURUSD")
        assertThat(second.map { it.symbol }).containsExactly("S1:EURUSD")
        assertThat(delegate.barRequestCount("S0:EURUSD")).isEqualTo(1)
    }

    private class RecordingSource(
        private val blockBars: Boolean = false,
    ) : MarketSource {
        override val name: String = "recording"

        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.BARS)

        private val barRequests = ConcurrentHashMap<String, AtomicInteger>()
        private val barStarted = CountDownLatch(1)
        private val barRelease = CountDownLatch(1)

        override fun supports(symbol: String): Boolean = symbol.startsWith("S0:")

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            barRequests.computeIfAbsent(symbol) { AtomicInteger() }.incrementAndGet()
            if (blockBars) {
                barStarted.countDown()
                check(barRelease.await(2, TimeUnit.SECONDS)) { "bar read was not released" }
            }
            val candle =
                Candle(
                    symbol = symbol,
                    open = BigDecimal("1.0"),
                    high = BigDecimal("1.1"),
                    low = BigDecimal("0.9"),
                    close = BigDecimal("1.05"),
                    volume = BigDecimal("10"),
                    startTime = range.from.toEpochMilli(),
                    endTime = range.to.toEpochMilli(),
                )
            return sequenceOf(candle)
        }

        override fun liveTicks(symbols: List<String>): TickFeed =
            throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, "RecordingSource")

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> = throw UnsupportedDataException(MarketSourceCapability.TICKS, "RecordingSource")

        fun barRequestCount(symbol: String): Int = barRequests[symbol]?.get() ?: 0

        fun awaitBarStarted(): Boolean = barStarted.await(2, TimeUnit.SECONDS)

        fun releaseBars() {
            barRelease.countDown()
        }
    }

    private companion object {
        private fun oneMinute(): TimeWindow = TimeWindow.parse("1m")

        private fun range(): TimeRange = TimeRange(Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L))
    }
}
