package com.qkt.marketdata.source

import com.qkt.marketdata.live.MarketDataFeedScope
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SharedFanInTickFeedTest {
    private val closed = CopyOnWriteArrayList<SharedSubscriberFeed>()

    private fun subscriber() = SharedSubscriberFeed({ closed += it }, 16)

    @Test
    fun `the merged feed ends only after every input has ended`() {
        val a = subscriber()
        val b = subscriber()
        val merged = SharedFanInTickFeed(listOf(a, b))

        a.offer(tick("A", 1L))
        assertThat(merged.next()!!.timestamp).isEqualTo(1L)
        a.ended(null)
        b.offer(tick("B", 2L))
        assertThat(merged.next()!!.timestamp).isEqualTo(2L)
        b.ended(null)

        assertThat(merged.next()).isNull()
        assertThat(merged.terminalFailureReason()).isNull()
    }

    @Test
    fun `an input ending with a failure ends the merged feed and closes the others`() {
        val a = subscriber()
        val b = subscriber()
        val merged = SharedFanInTickFeed(listOf(a, b))

        a.ended("venue gone")

        assertThat(merged.next()).isNull()
        assertThat(merged.terminalFailureReason()).isEqualTo("venue gone")
        assertThat(closed).contains(b)
        assertThat(merged.next()).isNull()
    }

    @Test
    fun `disconnects and reconnects of any input reach the merged feed's handlers`() {
        val a = subscriber()
        val b = subscriber()
        val merged = SharedFanInTickFeed(listOf(a, b))
        val events = CopyOnWriteArrayList<String>()
        merged.onDisconnect { events += "down:${it.symbols}" }
        merged.onReconnect { events += "up:${it.symbols}" }

        b.disconnected(MarketDataFeedScope(source = "s", symbols = listOf("B")))
        a.reconnected(MarketDataFeedScope(source = "s", symbols = listOf("A")))
        merged.close()

        assertThat(events).containsExactly("down:[B]", "up:[A]")
        assertThat(closed).containsExactlyInAnyOrder(a, b)
    }
}
