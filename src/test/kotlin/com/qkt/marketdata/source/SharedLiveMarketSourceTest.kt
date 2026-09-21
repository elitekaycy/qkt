package com.qkt.marketdata.source

import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SharedLiveMarketSourceTest {
    @Test
    fun `overlapping subscribers share one upstream feed per symbol`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)

        val first = shared.liveTicks(listOf("A", "B"))
        val second = shared.liveTicks(listOf("B", "C"))

        assertThat(delegate.openCount("A")).isEqualTo(1)
        assertThat(delegate.openCount("B")).isEqualTo(1)
        assertThat(delegate.openCount("C")).isEqualTo(1)

        val tick = tick("B", 1L)
        delegate.emit(tick)
        assertThat(first.next()).isEqualTo(tick)
        assertThat(second.next()).isEqualTo(tick)

        first.close()
        val later = tick("B", 2L)
        delegate.emit(later)
        assertThat(second.next()).isEqualTo(later)
        assertThat(delegate.closeCount("B")).isZero()

        second.close()
        assertThat(delegate.awaitClosed("B")).isTrue()
    }

    @Test
    fun `last subscriber closes upstream and a later subscriber opens a fresh feed`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)

        shared.liveTicks(listOf("A")).close()
        assertThat(delegate.awaitClosed("A")).isTrue()

        val replacement = shared.liveTicks(listOf("A"))
        assertThat(delegate.openCount("A")).isEqualTo(2)
        val tick = tick("A", 3L)
        delegate.emit(tick)
        assertThat(replacement.next()).isEqualTo(tick)
        replacement.close()
    }

    @Test
    fun `disconnect reconnect and terminal failure propagate to every subscriber`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)
        val first = shared.liveTicks(listOf("A"))
        val second = shared.liveTicks(listOf("A"))
        val disconnected = CountDownLatch(2)
        val reconnected = CountDownLatch(2)
        val scopes = CopyOnWriteArrayList<MarketDataFeedScope>()

        listOf(first, second).forEach { feed ->
            val lifecycle = feed as MarketDataLifecycleFeed
            lifecycle.onDisconnect { scope ->
                scopes.add(scope)
                disconnected.countDown()
            }
            lifecycle.onReconnect { reconnected.countDown() }
        }

        delegate.disconnect("A")
        assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(scopes).allSatisfy { scope ->
            assertThat(scope.source).isEqualTo("controlled")
            assertThat(scope.symbols).containsExactly("A")
        }

        delegate.reconnect("A")
        assertThat(reconnected.await(2, TimeUnit.SECONDS)).isTrue()

        delegate.fail("A", "venue unavailable")
        assertThat(first.next()).isNull()
        assertThat(second.next()).isNull()
        assertThat((first as MarketDataLifecycleFeed).terminalFailureReason()).isEqualTo("venue unavailable")
        assertThat((second as MarketDataLifecycleFeed).terminalFailureReason()).isEqualTo("venue unavailable")
    }
}
