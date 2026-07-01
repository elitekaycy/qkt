package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IbDefendedTest {
    private val day0 = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L
    private val minMs = 60_000L

    private fun candle(
        startTime: Long,
        high: String,
        low: String,
        close: String,
    ) = Candle(
        symbol = "X",
        open = BigDecimal(close),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal.ZERO,
        startTime = startTime,
        endTime = startTime + minMs,
    )

    @Test
    fun `null while the initial balance is still forming`() {
        val ib = IbDefended(sessionStartHour = 0, ibMinutes = 60, high = true)
        ib.update(candle(day0, "110", "100", "105")) // inside the first hour
        assertThat(ib.isReady).isFalse()
        assertThat(ib.value()).isNull()
    }

    @Test
    fun `latches once the IB high is tested and held after the window`() {
        val ib = IbDefended(sessionStartHour = 0, ibMinutes = 60, high = true)
        ib.update(candle(day0, "110", "100", "105")) // IB bar
        ib.update(candle(day0 + 30 * minMs, "112", "104", "108")) // IB high -> 112
        ib.update(candle(day0 + 60 * minMs, "111", "108", "110")) // post-IB, no touch
        assertThat(ib.isReady).isTrue()
        assertThat(ib.value()).isEqualByComparingTo("0")
        ib.update(candle(day0 + 90 * minMs, "113", "110", "111")) // touch 113>=112, close 111<112 -> held
        assertThat(ib.value()).isEqualByComparingTo("1")
        ib.update(candle(day0 + 120 * minMs, "111", "109", "110")) // stays latched this session
        assertThat(ib.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `a break that closes outside is not a defense`() {
        val ib = IbDefended(sessionStartHour = 0, ibMinutes = 60, high = true)
        ib.update(candle(day0, "110", "100", "105"))
        ib.update(candle(day0 + 60 * minMs, "115", "108", "114")) // touch 115>=110 but close 114>110 -> broke out
        assertThat(ib.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `resets each session`() {
        val ib = IbDefended(sessionStartHour = 0, ibMinutes = 60, high = true)
        ib.update(candle(day0, "110", "100", "105"))
        ib.update(candle(day0 + 90 * minMs, "113", "108", "109")) // defended day 0
        assertThat(ib.value()).isEqualByComparingTo("1")
        // Next day: new IB, no defense yet.
        ib.update(candle(day0 + dayMs, "120", "110", "115"))
        ib.update(candle(day0 + dayMs + 90 * minMs, "119", "112", "116")) // post-IB, no touch of 120
        assertThat(ib.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `low variant defends the IB low`() {
        val ib = IbDefended(sessionStartHour = 0, ibMinutes = 60, high = false)
        ib.update(candle(day0, "110", "100", "105")) // IB low 100
        ib.update(candle(day0 + 90 * minMs, "104", "99", "103")) // low 99<=100, close 103>100 -> held
        assertThat(ib.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `rejects non-positive parameters`() {
        assertThatThrownBy { IbDefended(sessionStartHour = 0, ibMinutes = 0, high = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IbDefended(sessionStartHour = 24, ibMinutes = 60, high = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
