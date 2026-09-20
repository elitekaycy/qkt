package com.qkt.app

import com.qkt.app.IndicatorWarmerPerStreamFixtures.RecordingMarketSource
import com.qkt.app.IndicatorWarmerPerStreamFixtures.candle
import com.qkt.app.IndicatorWarmerPerStreamFixtures.candleStart
import com.qkt.app.IndicatorWarmerPerStreamFixtures.now
import com.qkt.app.IndicatorWarmerPerStreamFixtures.pipeline
import com.qkt.candles.TimeWindow
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorWarmerPerStreamDispatchTest {
    @Test
    fun `per-stream warmup calls bars with the right window per symbol`() {
        val source =
            RecordingMarketSource(
                seed =
                    mapOf(
                        ("X" to TimeWindow.ONE_MINUTE) to
                            (0..4).map { candle("X", candleStart + (55 + it) * 60_000L, 60_000L) },
                        ("Y" to TimeWindow.ONE_HOUR) to
                            (0..2).map { candle("Y", candleStart - (2 - it) * 3_600_000L, 3_600_000L) },
                    ),
            )
        val warmer = IndicatorWarmer(source, pipeline(source))

        warmer.warmup(
            perStream =
                mapOf(
                    WarmupStream("X", TimeWindow.ONE_MINUTE) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 5),
                    WarmupStream("Y", TimeWindow.ONE_HOUR) to WarmupSpec.Bars(TimeWindow.ONE_HOUR, 3),
                ),
            now = now,
        )

        assertThat(source.barCalls).hasSize(2)
        val xCall = source.barCalls.single { it.first == "X" }
        val yCall = source.barCalls.single { it.first == "Y" }
        assertThat(xCall.second).isEqualTo(TimeWindow.ONE_MINUTE)
        assertThat(yCall.second).isEqualTo(TimeWindow.ONE_HOUR)
    }

    @Test
    fun `per-stream warmup skips symbols with WarmupSpec None`() {
        val source =
            RecordingMarketSource(
                seed =
                    mapOf(
                        ("X" to TimeWindow.ONE_MINUTE) to
                            listOf(candle("X", candleStart + 59 * 60_000L, 60_000L)),
                    ),
            )
        val warmer = IndicatorWarmer(source, pipeline(source))

        warmer.warmup(
            perStream =
                mapOf(
                    WarmupStream("X", TimeWindow.ONE_MINUTE) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 1),
                    WarmupStream("Y", TimeWindow.ONE_HOUR) to WarmupSpec.None,
                ),
            now = now,
        )

        assertThat(source.barCalls).hasSize(1)
        assertThat(source.barCalls.single().first).isEqualTo("X")
    }

    @Test
    fun `same symbol at two windows keeps both warmup requests`() {
        val source =
            RecordingMarketSource(
                seed =
                    mapOf(
                        ("X" to TimeWindow.ONE_MINUTE) to
                            (0..4).map { candle("X", candleStart + (55 + it) * 60_000L, 60_000L) },
                        ("X" to TimeWindow.ONE_HOUR) to
                            (0..2).map { candle("X", candleStart - (2 - it) * 3_600_000L, 3_600_000L) },
                    ),
            )
        val warmer = IndicatorWarmer(source, pipeline(source))

        warmer.warmup(
            perStream =
                mapOf(
                    WarmupStream("X", TimeWindow.ONE_MINUTE) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 5),
                    WarmupStream("X", TimeWindow.ONE_HOUR) to WarmupSpec.Bars(TimeWindow.ONE_HOUR, 3),
                ),
            now = now,
        )

        assertThat(source.barCalls.map { it.first to it.second })
            .containsExactlyInAnyOrder("X" to TimeWindow.ONE_MINUTE, "X" to TimeWindow.ONE_HOUR)
    }
}
