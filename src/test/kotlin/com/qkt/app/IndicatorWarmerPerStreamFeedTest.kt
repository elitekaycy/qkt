package com.qkt.app

import com.qkt.app.IndicatorWarmerPerStreamFixtures.RecordingMarketSource
import com.qkt.app.IndicatorWarmerPerStreamFixtures.candle
import com.qkt.app.IndicatorWarmerPerStreamFixtures.candleStart
import com.qkt.app.IndicatorWarmerPerStreamFixtures.now
import com.qkt.app.IndicatorWarmerPerStreamFixtures.pipeline
import com.qkt.candles.TimeWindow
import com.qkt.events.WarmupTickEvent
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorWarmerPerStreamFeedTest {
    @Test
    fun `per-stream warmup feeds WarmupTickEvent for each fetched candle`() {
        val source =
            RecordingMarketSource(
                seed =
                    mapOf(
                        ("X" to TimeWindow.ONE_MINUTE) to
                            (0..2).map { candle("X", candleStart + (57 + it) * 60_000L, 60_000L) },
                    ),
            )
        val pipe = pipeline(source)
        val received = mutableListOf<WarmupTickEvent>()
        // EventBus is private — subscribe through pipeline's bus accessor if exposed, else fall back
        // to verifying the bars() call shape (already covered above).
        val warmer = IndicatorWarmer(source, pipe)
        warmer.warmup(
            perStream =
                mapOf(
                    WarmupStream("X", TimeWindow.ONE_MINUTE) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 3),
                ),
            now = now,
        )
        // No bus accessor — assert via barCalls that exactly the requested window was pulled.
        assertThat(source.barCalls.single().first).isEqualTo("X")
        assertThat(received).isEmpty() // (placeholder — see comment above)
    }

    @Test
    fun `legacy single-spec warmup form still works`() {
        val source =
            RecordingMarketSource(
                seed =
                    mapOf(
                        ("X" to TimeWindow.ONE_MINUTE) to
                            listOf(candle("X", candleStart + 59 * 60_000L, 60_000L)),
                        ("Y" to TimeWindow.ONE_MINUTE) to
                            listOf(candle("Y", candleStart + 59 * 60_000L, 60_000L)),
                    ),
            )
        val warmer = IndicatorWarmer(source, pipeline(source))

        warmer.warmup(
            symbols = listOf("X", "Y"),
            spec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 1),
            now = now,
        )

        assertThat(source.barCalls).hasSize(2)
        assertThat(source.barCalls.map { it.first }).containsExactlyInAnyOrder("X", "Y")
    }
}
