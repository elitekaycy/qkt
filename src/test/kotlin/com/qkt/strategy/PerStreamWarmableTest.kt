package com.qkt.strategy

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerStreamWarmableTest {
    @Test
    fun `empty per-stream map means no warmup`() {
        val w =
            object : PerStreamWarmable {
                override val perStreamWarmup = emptyMap<WarmupStream, WarmupSpec>()
            }
        assertThat(w.perStreamWarmup).isEmpty()
    }

    @Test
    fun `per-stream warmup specs are addressable by symbol and window`() {
        val spec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 50)
        val stream = WarmupStream("BACKTEST:BTCUSDT", TimeWindow.ONE_MINUTE)
        val w =
            object : PerStreamWarmable {
                override val perStreamWarmup = mapOf(stream to spec)
            }
        assertThat(w.perStreamWarmup[stream]).isEqualTo(spec)
        assertThat(w.perStreamWarmup[WarmupStream("BACKTEST:BTCUSDT", TimeWindow.ONE_HOUR)]).isNull()
    }

    @Test
    fun `multiple streams can carry different specs`() {
        val w =
            object : PerStreamWarmable {
                override val perStreamWarmup =
                    mapOf(
                        WarmupStream("EXNESS:XAUUSD", TimeWindow.FIVE_MINUTES) to
                            WarmupSpec.Bars(TimeWindow.FIVE_MINUTES, 50),
                        WarmupStream("BACKTEST:SPX500", TimeWindow.ONE_HOUR) to
                            WarmupSpec.Bars(TimeWindow.ONE_HOUR, 24),
                    )
            }
        assertThat(w.perStreamWarmup).hasSize(2)
        assertThat(w.perStreamWarmup.keys.map { it.window })
            .containsExactlyInAnyOrder(TimeWindow.FIVE_MINUTES, TimeWindow.ONE_HOUR)
    }
}
