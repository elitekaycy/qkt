package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.events.RiskEvent
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionHaltTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    @Test
    fun `handle halt drives RiskState and resume reverses it on a live session`() {
        // Pins that handle.halt/resume delegate into RiskState. Order-rejection-on-halt
        // is covered by RiskEngineTest's `halt blocks new exposure but lets
        // risk-reducing orders out`.
        val feedLatch = CountDownLatch(1)
        val source =
            object : MarketSource {
                override val name: String = "BlockingTest"
                override val capabilities: Set<MarketSourceCapability> =
                    setOf(MarketSourceCapability.LIVE_TICKS)

                override fun supports(symbol: String): Boolean = true

                override fun liveTicks(symbols: List<String>): TickFeed =
                    object : TickFeed {
                        override fun next(): Tick? {
                            feedLatch.await()
                            return null
                        }
                    }
            }

        val clock = FixedClock(time = now.toEpochMilli())
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)

        // halt/resume publish their RiskEvent from the control (this) thread, so the live bus
        // routes them onto the engine loop — delivery is async, on the engine thread. Await it.
        val halted = mutableListOf<RiskEvent.Halted>()
        val resumed = mutableListOf<RiskEvent.Resumed>()
        val haltedSeen = CountDownLatch(1)
        val resumedSeen = CountDownLatch(1)
        bus.subscribe<RiskEvent.Halted> {
            halted.add(it)
            haltedSeen.countDown()
        }
        bus.subscribe<RiskEvent.Resumed> {
            resumed.add(it)
            resumedSeen.countDown()
        }

        val handle =
            LiveSession(
                strategies = emptyList(),
                rules = emptyList(),
                source = source,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                busOverride = bus,
            ).start()

        // Engine thread is blocked on feedLatch — session is alive, not terminated.
        assertThat(handle.isHalted()).isFalse()

        handle.halt("operator")
        // Flag is set synchronously on the control thread; the event is delivered on the engine thread.
        assertThat(handle.isHalted()).isTrue()
        assertThat(haltedSeen.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(halted).hasSize(1)
        assertThat(halted.first().reason).isEqualTo("operator")

        handle.resume()
        assertThat(handle.isHalted()).isFalse()
        assertThat(resumedSeen.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(resumed).hasSize(1)

        // Let the engine thread drain and exit cleanly.
        feedLatch.countDown()
        handle.stop()
    }
}
