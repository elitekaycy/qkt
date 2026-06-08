package com.qkt.common

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NyseCalendarTest {
    private val cal = TradingCalendar.nyse()

    @Test
    fun `name is nyse`() {
        assertThat(cal.name).isEqualTo("nyse")
    }

    @Test
    fun `weekday in session window is open`() {
        val t = Instant.parse("2024-01-16T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isTrue()
    }

    @Test
    fun `weekday before 9_30 ET is closed`() {
        val t = Instant.parse("2024-01-16T13:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `weekday after 16_00 ET is closed`() {
        val t = Instant.parse("2024-01-16T22:30:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `saturday is closed`() {
        val t = Instant.parse("2024-01-13T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `MLK day 2024 is closed`() {
        val t = Instant.parse("2024-01-15T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `christmas day 2024 is closed`() {
        val t = Instant.parse("2024-12-25T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `independence day 2024 is closed`() {
        val t = Instant.parse("2024-07-04T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `previous day anchor returns previous trading day`() {
        val mon = Instant.parse("2024-01-16T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, mon)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-12T14:30:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-12T21:00:00Z"))
    }

    @Test
    fun `concurrent holiday lookups across many years do not corrupt the cache`() {
        val years = (1990..2090).toList()
        val errors = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(16)
        try {
            (0 until 16)
                .map {
                    pool.submit {
                        try {
                            repeat(4) {
                                for (y in years) {
                                    cal.isInSession("AAPL", Instant.parse("$y-06-16T15:00:00Z"))
                                }
                            }
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(errors).isEmpty()
        // The cache still returns correct holidays after concurrent population.
        assertThat(cal.isInSession("AAPL", Instant.parse("2024-07-04T15:00:00Z"))).isFalse()
    }
}
