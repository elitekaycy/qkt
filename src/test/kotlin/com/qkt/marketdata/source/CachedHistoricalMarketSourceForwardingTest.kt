package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CachedHistoricalMarketSourceForwardingTest {
    private val minute = TimeWindow(60_000L)

    @Test
    fun `tick reads capabilities and live feeds pass straight to the delegate`() {
        val probe = DelegationProbe()
        val source = CachedHistoricalMarketSource(probe)

        assertThat(source.capabilitiesFor("X")).containsExactly(MarketSourceCapability.TICKS)
        assertThat(source.liveTicks(listOf("X"))).isSameAs(probe.feed)
        assertThat(source.ticks("X", probeRange()).single().timestamp).isEqualTo(1L)
        assertThat(source.tickSlice("X", 5L, 9L).single().timestamp).isEqualTo(2L)
        assertThat(probe.calls).containsExactly("capabilitiesFor:X", "liveTicks:[X]", "ticks:X", "tickSlice:X:5:9")
    }

    @Test
    fun `a cached read past its ttl is loaded again`() {
        val probe = DelegationProbe()
        val clock = FixedClock(0L)
        val source = CachedHistoricalMarketSource(probe, ttlMs = 100L, clock = clock)

        source.bars("X", minute, probeRange()).toList()
        clock.time = 100L
        source.bars("X", minute, probeRange()).toList()
        clock.time = 201L
        source.bars("X", minute, probeRange()).toList()

        assertThat(probe.calls).containsExactly("bars:X:60000", "bars:X:60000")
    }

    @Test
    fun `forgetting bars drops only that symbol and width`() {
        val probe = DelegationProbe()
        val source = CachedHistoricalMarketSource(probe, clock = FixedClock(0L))
        source.bars("X", minute, probeRange()).toList()
        source.bars("Y", minute, probeRange()).toList()
        source.bars("X", TimeWindow(300_000L), probeRange(0L, 300_000L)).toList()

        source.forgetBars("X", minute)
        source.bars("X", minute, probeRange()).toList()
        source.bars("Y", minute, probeRange()).toList()
        source.bars("X", TimeWindow(300_000L), probeRange(0L, 300_000L)).toList()

        assertThat(probe.calls).containsExactly("bars:X:60000", "bars:Y:60000", "bars:X:300000", "bars:X:60000")
    }

    @Test
    fun `closing clears the cache and closes the delegate`() {
        val probe = DelegationProbe()
        val source = CachedHistoricalMarketSource(probe, clock = FixedClock(0L))
        source.bars("X", minute, probeRange()).toList()

        source.close()
        source.bars("X", minute, probeRange()).toList()

        assertThat(probe.closed).isEqualTo(1)
        assertThat(probe.calls).containsExactly("bars:X:60000", "bars:X:60000")
    }

    @Test
    fun `closing over a delegate that is not closeable only clears the cache`() {
        val memory = InMemoryMarketSource().apply { seedBars("X", minute, listOf(probeCandle("X", 0L, 60_000L))) }
        val source = CachedHistoricalMarketSource(memory)

        source.close()

        assertThat(source.bars("X", minute, probeRange()).toList()).containsExactly(probeCandle("X", 0L, 60_000L))
    }

    @Test
    fun `a failed load is not cached and the next caller retries`() {
        val probe = DelegationProbe().apply { barFailure = IllegalStateException("venue down") }
        val source = CachedHistoricalMarketSource(probe, clock = FixedClock(0L))

        assertThatThrownBy { source.bars("X", minute, probeRange()) }.hasMessage("venue down")
        probe.barFailure = null
        val bars = source.bars("X", minute, probeRange()).toList()

        assertThat(bars).hasSize(1)
        assertThat(probe.calls).hasSize(2)
    }

    @Test
    fun `a negative ttl or empty cache is refused`() {
        assertThatThrownBy { CachedHistoricalMarketSource(DelegationProbe(), ttlMs = -1L) }
            .hasMessageContaining("non-negative")
        assertThatThrownBy { CachedHistoricalMarketSource(DelegationProbe(), maxEntries = 0) }
            .hasMessageContaining("positive")
    }
}
