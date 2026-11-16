package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SessionVwapTest {
    private fun flat(
        ts: String,
        typicalPrice: String,
        volume: String,
    ): Candle {
        val p = BigDecimal(typicalPrice)
        val ms = Instant.parse(ts).toEpochMilli()
        return Candle(
            "X",
            open = p,
            high = p,
            low = p,
            close = p,
            volume = BigDecimal(volume),
            startTime = ms,
            endTime =
                ms + 60_000,
        )
    }

    @Test
    fun `null before any volume observed`() {
        val s = SessionVwap(anchorHour = 0)
        assertThat(s.value()).isNull()
        assertThat(s.bands()).isNull()
    }

    @Test
    fun `vwap weights typical price by volume`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "10", "10"))
        s.update(flat("2026-01-01T01:00:00Z", "20", "30"))
        // (10*10 + 20*30) / (10+30) = 700/40 = 17.5
        assertThat(s.value()).isEqualByComparingTo("17.5")
    }

    @Test
    fun `typical price is high plus low plus close over three`() {
        val s = SessionVwap(anchorHour = 0)
        val ms = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        // tp = (30 + 15 + 15) / 3 = 20
        s.update(
            Candle(
                "X",
                open = BigDecimal("18"),
                high = BigDecimal("30"),
                low = BigDecimal("15"),
                close = BigDecimal("15"),
                volume = BigDecimal("5"),
                startTime = ms,
                endTime =
                    ms + 60_000,
            ),
        )
        assertThat(s.value()).isEqualByComparingTo("20")
    }

    @Test
    fun `resets at the midnight anchor`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "10", "10"))
        s.update(flat("2026-01-02T00:00:00Z", "50", "5"))
        // New UTC day → accumulators reset → vwap is just the day-2 candle.
        assertThat(s.value()).isEqualByComparingTo("50")
    }

    @Test
    fun `noon anchor splits the day at twelve UTC`() {
        val s = SessionVwap(anchorHour = 12)
        s.update(flat("2025-12-31T12:00:00Z", "10", "10"))
        s.update(flat("2026-01-01T12:00:00Z", "20", "10"))
        assertThat(s.value()).isEqualByComparingTo("20")
    }

    @Test
    fun `first partial session stays undefined`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T03:00:00Z", "10", "10"))
        s.update(flat("2026-01-01T20:00:00Z", "20", "10"))
        assertThat(s.value()).isNull()
    }

    @Test
    fun `stdev is volume-weighted dispersion around vwap`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "10", "10"))
        s.update(flat("2026-01-01T01:00:00Z", "20", "10"))
        // vwap = 15; var = (10*25 + 10*25)/20 = 25; stdev = 5
        assertThat(s.bands()!!.vwap).isEqualByComparingTo("15")
        assertThat(s.bands()!!.stdev).isEqualByComparingTo("5")
    }

    @Test
    fun `stdev weights dispersion by volume over several bars`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "10", "1"))
        s.update(flat("2026-01-01T01:00:00Z", "20", "2"))
        s.update(flat("2026-01-01T02:00:00Z", "40", "1"))
        // vwap = (10 + 40 + 40) / 4 = 22.5; var = (156.25 + 2*6.25 + 306.25) / 4 = 118.75
        assertThat(s.bands()!!.vwap).isEqualByComparingTo("22.5")
        assertThat(s.bands()!!.stdev).isEqualByComparingTo("10.89724736")
    }

    @Test
    fun `stdev is exactly zero on a session's first bar at a repeating typical price`() {
        val s = SessionVwap(anchorHour = 0)
        val ms = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        // tp = 195000.04 / 3 = 65000.0133... repeats; E[tp^2] - E[tp]^2 at 16 digits read ~3e-6 here.
        s.update(
            Candle(
                "X",
                open = BigDecimal("65000.01"),
                high = BigDecimal("65000.02"),
                low = BigDecimal("65000.01"),
                close = BigDecimal("65000.01"),
                volume = BigDecimal("12.5"),
                startTime = ms,
                endTime = ms + 60_000,
            ),
        )
        assertThat(s.bands()!!.stdev).isEqualByComparingTo("0")
    }

    @Test
    fun `stdev of a near-constant session at a billion-scale price is exact`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "1234567890.12", "3"))
        s.update(flat("2026-01-01T01:00:00Z", "1234567890.14", "3"))
        s.update(flat("2026-01-01T02:00:00Z", "1234567890.13", "6"))
        // tp^2 needs 24 digits, so the one-pass variance at 16 digits is noise; the exact answer is
        // vwap = 1234567890.13 and var = (3*1e-4 + 3*1e-4 + 0) / 12 = 5e-5 → stdev 0.00707107.
        assertThat(s.bands()!!.vwap).isEqualByComparingTo("1234567890.13")
        assertThat(s.bands()!!.stdev).isEqualByComparingTo("0.00707107")
    }

    @Test
    fun `stdev restarts from zero at the anchor`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "10", "10"))
        s.update(flat("2026-01-01T01:00:00Z", "20", "10"))
        s.update(flat("2026-01-02T00:00:00Z", "65000.5", "10"))
        assertThat(s.bands()!!.vwap).isEqualByComparingTo("65000.5")
        assertThat(s.bands()!!.stdev).isEqualByComparingTo("0")
    }

    @Test
    fun `zero-volume candle carries no weight`() {
        val s = SessionVwap(anchorHour = 0)
        s.update(flat("2026-01-01T00:00:00Z", "999", "0"))
        s.update(flat("2026-01-01T01:00:00Z", "20", "10"))
        assertThat(s.value()).isEqualByComparingTo("20")
    }

    @Test
    fun `rejects anchor hour outside zero to twenty-three`() {
        assertThatThrownBy { SessionVwap(anchorHour = 24) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
