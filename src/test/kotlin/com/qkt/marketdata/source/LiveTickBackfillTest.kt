package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveTickBackfillTest {
    private fun at(hhmmss: String) = Instant.parse("2026-09-21T$hhmmss.000Z").toEpochMilli()

    private fun tick(hhmmss: String) = Tick("BTC", BigDecimal.ONE, at(hhmmss))

    @Test
    fun `a subscription starts at the start of its minute`() {
        assertThat(LiveTickBackfill.since(at("17:03:52"))).isEqualTo(at("17:03:00"))
    }

    @Test
    fun `but never more than fifty-five seconds back, clear of the gate's sixty-second skew limit`() {
        assertThat(LiveTickBackfill.since(at("17:03:58"))).isEqualTo(at("17:03:03"))
    }

    @Test
    fun `recent ticks hand over only the backfill window, and forget what is older than a minute`() {
        val recent = RecentTicks()
        listOf("17:02:20", "17:02:59", "17:03:10", "17:03:49").forEach { recent.add(tick(it), at("17:03:50")) }

        assertThat(
            recent.backfillFor(at("17:03:52")).map { it.timestamp },
        ).containsExactly(at("17:03:10"), at("17:03:49"))
        assertThat(recent.backfillFor(at("17:04:30")).map { it.timestamp }).`as`("a new minute starts empty").isEmpty()
    }
}
