package com.qkt.strategy

import com.qkt.candles.TimeWindow
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarmupSpecTest {
    private val now = Instant.parse("2026-05-05T15:00:00Z")

    @Test
    fun `None windowMs is zero`() {
        assertThat(WarmupSpec.None.windowMs(now)).isEqualTo(0L)
    }

    @Test
    fun `Bars windowMs is window duration times count`() {
        val spec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30)
        assertThat(spec.windowMs(now)).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `Duration windowMs returns the duration in ms`() {
        val spec = WarmupSpec.Duration(TimeWindow.FIVE_MINUTES, Duration.ofHours(2))
        assertThat(spec.windowMs(now)).isEqualTo(2 * 3_600_000L)
    }

    @Test
    fun `Ticks windowMs returns the duration in ms`() {
        val spec = WarmupSpec.Ticks(Duration.ofMinutes(5))
        assertThat(spec.windowMs(now)).isEqualTo(5 * 60_000L)
    }

    @Test
    fun `Bars requires positive count`() {
        assertThatThrownBy {
            WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `widest spec wins via maxByOrNull windowMs`() {
        val small = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 10)
        val medium = WarmupSpec.Duration(TimeWindow.ONE_MINUTE, Duration.ofHours(1))
        val large = WarmupSpec.Bars(TimeWindow.ONE_HOUR, count = 5)
        val widest = listOf(small, medium, large).maxByOrNull { it.windowMs(now) }
        assertThat(widest).isEqualTo(large)
    }

    @Test
    fun `strategy can implement Strategy and Warmable`() {
        val s =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 10)

                override fun onTick(
                    tick: com.qkt.marketdata.Tick,
                    emit: (Signal) -> Unit,
                ) {}
            }
        assertThat(s.warmup).isEqualTo(WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 10))
    }
}
