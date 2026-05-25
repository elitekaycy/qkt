package com.qkt.strategy

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerStreamWarmableTest {
    @Test
    fun `empty per-stream map means no warmup`() {
        val w =
            object : PerStreamWarmable {
                override val perStreamWarmup = emptyMap<String, WarmupSpec>()
            }
        assertThat(w.perStreamWarmup).isEmpty()
    }

    @Test
    fun `per-stream warmup specs are addressable by qkt symbol`() {
        val spec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 50)
        val w =
            object : PerStreamWarmable {
                override val perStreamWarmup = mapOf("BACKTEST:BTCUSDT" to spec)
            }
        assertThat(w.perStreamWarmup["BACKTEST:BTCUSDT"]).isEqualTo(spec)
        assertThat(w.perStreamWarmup["EXNESS:XAUUSD"]).isNull()
    }

    @Test
    fun `multiple streams can carry different specs`() {
        val w =
            object : PerStreamWarmable {
                override val perStreamWarmup =
                    mapOf(
                        "EXNESS:XAUUSD" to WarmupSpec.Bars(TimeWindow.FIVE_MINUTES, 50),
                        "BACKTEST:SPX500" to WarmupSpec.Bars(TimeWindow.ONE_HOUR, 24),
                    )
            }
        assertThat(w.perStreamWarmup).hasSize(2)
        assertThat(w.perStreamWarmup["EXNESS:XAUUSD"]).isInstanceOf(WarmupSpec.Bars::class.java)
        assertThat(w.perStreamWarmup["BACKTEST:SPX500"]).isInstanceOf(WarmupSpec.Bars::class.java)
    }
}
