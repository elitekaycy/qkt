package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SessionRangeTest {
    private fun bar(
        ts: String,
        high: String,
        low: String,
    ): Candle {
        val ms = Instant.parse(ts).toEpochMilli()
        return Candle(
            "X",
            open = BigDecimal(low),
            high = BigDecimal(high),
            low = BigDecimal(low),
            close = BigDecimal(low),
            volume = BigDecimal.ONE,
            startTime = ms,
            endTime = ms + 60_000,
        )
    }

    // Asian window 00:00-07:00 UTC.
    private fun asian() = SessionRange(startHour = 0, startMinute = 0, endHour = 7, endMinute = 0)

    @Test
    fun `null until the first window completes`() {
        val sr = asian()
        sr.update(bar("2026-01-01T00:00:00Z", "10", "5"))
        sr.update(bar("2026-01-01T06:00:00Z", "12", "4"))
        // Still inside the window — no completed instance yet.
        assertThat(sr.value()).isNull()
        assertThat(sr.range()).isNull()
    }

    @Test
    fun `latches the completed window range and holds it through the later window`() {
        val sr = asian()
        sr.update(bar("2026-01-01T00:00:00Z", "10", "5"))
        sr.update(bar("2026-01-01T03:00:00Z", "12", "4"))
        sr.update(bar("2026-01-01T06:00:00Z", "11", "6"))
        sr.update(bar("2026-01-01T07:00:00Z", "20", "1")) // out of window → latch
        assertThat(sr.range()!!.high).isEqualByComparingTo("12")
        assertThat(sr.range()!!.low).isEqualByComparingTo("4")
        // A later London candle does NOT move the latched Asian range.
        sr.update(bar("2026-01-01T09:00:00Z", "25", "2"))
        assertThat(sr.range()!!.high).isEqualByComparingTo("12")
        assertThat(sr.range()!!.low).isEqualByComparingTo("4")
    }

    @Test
    fun `holds the prior day's range until the next window completes`() {
        val sr = asian()
        sr.update(bar("2026-01-01T03:00:00Z", "12", "4"))
        sr.update(bar("2026-01-01T07:00:00Z", "20", "1")) // latch day 1 → 12/4
        sr.update(bar("2026-01-02T00:30:00Z", "99", "0")) // day 2 window in progress
        assertThat(sr.range()!!.high).isEqualByComparingTo("12") // still day 1
        assertThat(sr.range()!!.low).isEqualByComparingTo("4")
        sr.update(bar("2026-01-02T07:30:00Z", "30", "8")) // out of window → latch day 2
        assertThat(sr.range()!!.high).isEqualByComparingTo("99")
        assertThat(sr.range()!!.low).isEqualByComparingTo("0")
    }

    @Test
    fun `wrap-midnight window accumulates across the day boundary`() {
        val sr = SessionRange(startHour = 22, startMinute = 0, endHour = 2, endMinute = 0)
        sr.update(bar("2026-01-01T22:30:00Z", "10", "5"))
        sr.update(bar("2026-01-01T23:30:00Z", "12", "4"))
        sr.update(bar("2026-01-02T01:30:00Z", "8", "3")) // still same instance (started 22:00)
        sr.update(bar("2026-01-02T03:00:00Z", "50", "50")) // out of window → latch
        assertThat(sr.range()!!.high).isEqualByComparingTo("12")
        assertThat(sr.range()!!.low).isEqualByComparingTo("3")
    }

    @Test
    fun `rejects a degenerate zero-length window`() {
        assertThatThrownBy { SessionRange(startHour = 7, startMinute = 0, endHour = 7, endMinute = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects an out-of-range hour`() {
        assertThatThrownBy { SessionRange(startHour = 24, startMinute = 0, endHour = 7, endMinute = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
