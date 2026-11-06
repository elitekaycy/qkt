package com.qkt.strategy

import com.qkt.candles.TimeWindow
import com.qkt.common.RefreshTrigger
import java.time.Duration
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarmupSpecRejectionTest {
    @Test
    fun `duration and tick warmups must be positive`() {
        assertThatThrownBy { WarmupSpec.Duration(TimeWindow.ONE_HOUR, Duration.ZERO) }
            .hasMessageContaining("WarmupSpec.Duration must be positive")
        assertThatThrownBy { WarmupSpec.Duration(TimeWindow.ONE_HOUR, Duration.ofSeconds(-1)) }
            .hasMessageContaining("WarmupSpec.Duration must be positive")
        assertThatThrownBy { WarmupSpec.Ticks(Duration.ZERO) }
            .hasMessageContaining("WarmupSpec.Ticks duration must be positive")
        assertThatThrownBy { WarmupSpec.Ticks(Duration.ofMinutes(-2)) }
            .hasMessageContaining("WarmupSpec.Ticks duration must be positive")
    }

    @Test
    fun `a window that is not a whole second prints in milliseconds`() {
        assertThat(TimeWindow(1_500L).canonicalSpec()).isEqualTo("1500ms")
        assertThat(TimeWindow(1_500L).durationMs).isEqualTo(1_500L)
    }

    @Test
    fun `a time of day refresh keeps its time`() {
        val trigger = RefreshTrigger.OnTimeOfDay(LocalTime.of(17, 0))
        assertThat(trigger.time).isEqualTo(LocalTime.of(17, 0))
        assertThat(trigger).isEqualTo(RefreshTrigger.OnTimeOfDay(LocalTime.of(17, 0)))
    }
}
