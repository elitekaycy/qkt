package com.qkt.strategy

import com.qkt.candles.TimeWindow
import com.qkt.strategy.samples.RollingHighBreakoutStrategy
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RollingHighBreakoutWarmupTest {
    @Test
    fun `warmup asks for one bar per window in the lookback and at least one`() {
        val daily = RollingHighBreakoutStrategy("XAUUSD", lookback = Duration.ofDays(3), window = TimeWindow.ONE_HOUR)
        val short = RollingHighBreakoutStrategy("XAUUSD", lookback = Duration.ofMinutes(5), window = TimeWindow.ONE_HOUR)

        assertThat(daily.warmup).isEqualTo(WarmupSpec.Bars(TimeWindow.ONE_HOUR, 72))
        assertThat(short.warmup).isEqualTo(WarmupSpec.Bars(TimeWindow.ONE_HOUR, 1))
    }

    @Test
    fun `a zero or negative lookback is rejected`() {
        assertThatThrownBy { RollingHighBreakoutStrategy("XAUUSD", lookback = Duration.ZERO) }
            .hasMessageContaining("lookback must be positive")
        assertThatThrownBy { RollingHighBreakoutStrategy("XAUUSD", lookback = Duration.ofHours(-1)) }
            .hasMessageContaining("lookback must be positive")
    }

    @Test
    fun `the empty structure view knows no structures or marks`() {
        assertThat(StructureView.EMPTY.live("spread")).isNull()
        assertThat(StructureView.EMPTY.all()).isEmpty()
        assertThat(StructureView.EMPTY.mark("BTC-PERP")).isNull()
    }
}
