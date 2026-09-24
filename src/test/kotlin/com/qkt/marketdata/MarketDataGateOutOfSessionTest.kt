package com.qkt.marketdata

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.common.Money
import com.qkt.common.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class MarketDataGateOutOfSessionTest {
    private class TickingClock(
        var t: Long = 0L,
    ) : MutableClock {
        override fun now(): Long = t

        override fun advanceTo(timestamp: Long) {
            t = timestamp
        }
    }

    private val logger = LoggerFactory.getLogger(MarketDataGate::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val alerts = mutableListOf<String>()
    private val recoveries = mutableListOf<String>()
    private var open = true

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun lines(level: Level): List<String> =
        appender.list.filter { it.level == level }.map { it.formattedMessage }

    private fun gate(clock: TickingClock) =
        MarketDataGate(
            clock,
            minStaleAgeMs = 1_000L,
            onUnhealthy = { symbol, reason, _ -> alerts.add("$symbol:$reason") },
            onRecovered = { symbol, reason, _ -> recoveries.add("$symbol:$reason") },
            inSession = { _, _ -> open },
        )

    private fun tick(ts: Long) = Tick("X", Money.of("100"), ts)

    @Test
    fun `a quote gap while the venue is out of session is not stale`() {
        // Friday close: the last print lands, then the calendar closes for the weekend.
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(tick(clock.t))
        open = false
        clock.t += 48L * 3_600_000L

        repeat(3) { assertThat(gate.isHealthy("X")).isFalse() }

        assertThat(alerts).isEmpty()
        assertThat(lines(Level.ERROR)).isEmpty()
        assertThat(lines(Level.INFO))
            .filteredOn { it.contains("venue closed (out of session)") }
            .singleElement()
            .asString()
            .contains("market data for X")
            .contains("quote age 172800000ms")
    }

    @Test
    fun `the first fresh tick after the closed gap clears it without a recovery event`() {
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(tick(clock.t))
        open = false
        clock.t += 48L * 3_600_000L
        assertThat(gate.isHealthy("X")).isFalse()

        open = true
        clock.t += 1_000L
        gate.observe(tick(clock.t))

        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(lines(Level.INFO)).anyMatch { it.contains("fresh print after venue gap; healthy again") }
        assertThat(alerts).isEmpty()
        assertThat(recoveries).isEmpty()
    }

    @Test
    fun `a gap still open when the session reopens is stale`() {
        // Monday open with no print: the calendar says trade, the feed says nothing.
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(tick(clock.t))
        open = false
        clock.t += 48L * 3_600_000L
        assertThat(gate.isHealthy("X")).isFalse()

        open = true
        clock.t += 5_000L
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).singleElement().asString().contains("X:quote age")

        clock.t += 2_000L
        gate.observe(tick(clock.t))
        assertThat(recoveries).containsExactly("X:fresh tick after stale")
    }

    @Test
    fun `a gap that went stale in session stays stale after the session closes`() {
        // Friday feed fault: stale before the close, and the close does not turn it into a pause.
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(tick(clock.t))
        clock.t += 2_000L
        assertThat(gate.isHealthy("X")).isFalse()

        open = false
        clock.t += 3_600_000L
        assertThat(gate.isHealthy("X")).isFalse()

        assertThat(alerts).hasSize(1)
        assertThat(recoveries).isEmpty()
        assertThat(lines(Level.INFO)).noneMatch { it.contains("out of session") }
    }
}
