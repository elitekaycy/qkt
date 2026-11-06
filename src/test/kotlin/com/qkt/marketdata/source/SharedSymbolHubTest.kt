package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SharedSymbolHubTest {
    private class FeedSource(
        private val open: () -> TickFeed,
    ) : MarketSource {
        override val name = "feeds"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = open()
    }

    private val ended = AtomicInteger()

    private fun hub(open: () -> TickFeed) =
        SharedSymbolHub(FeedSource(open), "feeds", "A", 16, FixedClock(0L)) { ended.incrementAndGet() }

    private fun feed(read: () -> Tick?): TickFeed =
        object : TickFeed {
            override fun next(): Tick? = read()
        }

    @Test
    fun `an upstream that cannot open closes the hub and rethrows`() {
        val hub = hub { throw IllegalStateException("no session") }

        assertThatThrownBy { hub.subscribe() }.hasMessage("no session")
        assertThat(hub.isClosed()).isTrue()
        assertThat(hub.subscribe()).isNull()
        assertThat(ended.get()).isEqualTo(1)
    }

    @Test
    fun `a finite upstream that runs out ends subscribers without a failure`() {
        val subscriber = hub { feed { null } }.subscribe()!!

        assertThat(subscriber.next()).isNull()
        assertThat(subscriber.terminalFailureReason()).isNull()
    }

    @Test
    fun `an interrupted upstream read ends subscribers without a failure`() {
        val subscriber = hub { feed { throw InterruptedException() } }.subscribe()!!

        assertThat(subscriber.next()).isNull()
        assertThat(subscriber.terminalFailureReason()).isNull()
    }

    @Test
    fun `an upstream read that throws ends subscribers naming the failure`() {
        val subscriber = hub { feed { throw IllegalStateException("decode error") } }.subscribe()!!

        assertThat(subscriber.next()).isNull()
        assertThat(subscriber.terminalFailureReason())
            .isEqualTo("shared market-data publisher failed: decode error")
    }

    @Test
    fun `a failure without a message is named by its type`() {
        val subscriber = hub { feed { throw UnsupportedOperationException() } }.subscribe()!!

        assertThat(subscriber.next()).isNull()
        assertThat(subscriber.terminalFailureReason())
            .isEqualTo("shared market-data publisher failed: UnsupportedOperationException")
    }

    @Test
    fun `closing the hub ends every subscriber closes upstream and runs once`() {
        val delegate = ControllableSource()
        val hub = SharedSymbolHub(delegate, "c", "A", 16, FixedClock(0L)) { ended.incrementAndGet() }
        val first = hub.subscribe()!!
        val second = hub.subscribe()!!

        hub.close()
        hub.close()

        assertThat(first.next()).isNull()
        assertThat(second.next()).isNull()
        assertThat(first.terminalFailureReason()).isNull()
        assertThat(delegate.awaitClosed("A")).isTrue()
        assertThat(delegate.closeCount("A")).isEqualTo(1)
        assertThat(ended.get()).isEqualTo(1)
        assertThat(hub.isClosed()).isTrue()
    }

    @Test
    fun `a full subscriber queue drops its oldest tick`() {
        val subscriber = SharedSubscriberFeed({}, 1)

        subscriber.offer(tick("A", 1L))
        subscriber.offer(tick("A", 2L))

        assertThat(subscriber.next()!!.timestamp).isEqualTo(2L)
    }
}
