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
        s.update(flat("2026-01-01T00:30:00Z", "10", "10"))
        s.update(flat("2026-01-02T00:30:00Z", "50", "5"))
        // New UTC day → accumulators reset → vwap is just the day-2 candle.
        assertThat(s.value()).isEqualByComparingTo("50")
    }

    @Test
    fun `noon anchor splits the day at twelve UTC`() {
        val s = SessionVwap(anchorHour = 12)
        s.update(flat("2026-01-01T11:00:00Z", "10", "10")) // belongs to prior session (anchored 12:00 Dec-31)
        s.update(flat("2026-01-01T13:00:00Z", "20", "10")) // new session anchored 12:00 Jan-01 → reset
        assertThat(s.value()).isEqualByComparingTo("20")
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
