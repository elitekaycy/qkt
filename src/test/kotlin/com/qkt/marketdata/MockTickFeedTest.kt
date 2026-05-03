package com.qkt.marketdata

import com.qkt.common.FixedClock
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MockTickFeedTest {
    @Test
    fun `next returns count ticks then null`() {
        val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 3, clock = FixedClock(1000L))
        assertThat(feed.next()).isNotNull
        assertThat(feed.next()).isNotNull
        assertThat(feed.next()).isNotNull
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `same seed produces identical tick sequence`() {
        val feed1 = MockTickFeed("XAUUSD", 2400.0, 5, FixedClock(1000L), random = Random(seed = 42L))
        val feed2 = MockTickFeed("XAUUSD", 2400.0, 5, FixedClock(1000L), random = Random(seed = 42L))
        repeat(5) {
            assertThat(feed1.next()?.price).isEqualTo(feed2.next()?.price)
        }
    }

    @Test
    fun `prices stay positive and finite`() {
        val feed = MockTickFeed("XAUUSD", 2400.0, count = 100, FixedClock(1000L))
        var tick = feed.next()
        while (tick != null) {
            assertThat(tick.price).isGreaterThan(0.0).isFinite()
            tick = feed.next()
        }
    }

    @Test
    fun `each tick has clock's start time plus interval offset`() {
        val clock = FixedClock(1714723200000L)
        val feed = MockTickFeed("XAUUSD", 2400.0, count = 3, clock = clock, tickIntervalMs = 1_000L)
        val tick0 = feed.next()
        val tick1 = feed.next()
        val tick2 = feed.next()
        assertThat(tick0?.timestamp).isEqualTo(1714723200000L)
        assertThat(tick1?.timestamp).isEqualTo(1714723201000L)
        assertThat(tick2?.timestamp).isEqualTo(1714723202000L)
    }

    @Test
    fun `tickIntervalMs default is 1000L`() {
        val clock = FixedClock(0L)
        val feed = MockTickFeed("X", 100.0, count = 2, clock = clock)
        assertThat(feed.next()?.timestamp).isEqualTo(0L)
        assertThat(feed.next()?.timestamp).isEqualTo(1_000L)
    }

    @Test
    fun `throws on non-positive tickIntervalMs`() {
        assertThatThrownBy { MockTickFeed("X", 100.0, 1, FixedClock(0L), tickIntervalMs = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MockTickFeed("X", 100.0, 1, FixedClock(0L), tickIntervalMs = -1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `throws on negative count`() {
        assertThatThrownBy { MockTickFeed("XAUUSD", 2400.0, count = -1, FixedClock(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `throws on non-positive startPrice`() {
        assertThatThrownBy { MockTickFeed("XAUUSD", startPrice = 0.0, count = 1, FixedClock(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MockTickFeed("XAUUSD", startPrice = -1.0, count = 1, FixedClock(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
