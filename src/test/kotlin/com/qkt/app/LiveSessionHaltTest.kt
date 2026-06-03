package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.events.RiskEvent
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionHaltTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    @Test
    fun `handle halt publishes RiskEvent Halted and resume publishes Resumed`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(Tick("X", Money.of("100"), now.toEpochMilli())),
        )
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
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                busOverride = bus,
            ).start()

        handle.awaitTermination(Duration.ofSeconds(2))

        handle.halt("operator")
        assertThat(halted).hasSize(1)
        assertThat(halted.first().reason).isEqualTo("operator")

        handle.resume()
        assertThat(resumed).hasSize(1)
    }
}
