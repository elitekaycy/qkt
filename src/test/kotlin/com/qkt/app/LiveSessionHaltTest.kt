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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionHaltTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    @Test
    fun `handle halt drives RiskState and resume reverses it on a live session`() {
        // Pins that handle.halt/resume delegate into RiskState — order-rejection-on-halt
        // itself is covered by RiskStateTest and RiskEngineTest.
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

        val halted = mutableListOf<RiskEvent.Halted>()
        val resumed = mutableListOf<RiskEvent.Resumed>()
        bus.subscribe<RiskEvent.Halted> { halted.add(it) }
        bus.subscribe<RiskEvent.Resumed> { resumed.add(it) }

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
        assertThat(halted).hasSize(1)
        assertThat(halted.first().reason).isEqualTo("operator")
        assertThat(handle.isHalted()).isTrue()

        handle.resume()
        assertThat(resumed).hasSize(1)
        assertThat(handle.isHalted()).isFalse()

        // Let the engine thread drain and exit cleanly.
        feedLatch.countDown()
        handle.stop()
    }
}
