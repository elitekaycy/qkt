package com.qkt.cli.golden

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * GBPUSD 21:30-21:35 on 2026-09-21: the venue's M5 bar (spread 42) lifted to mid gave a high of
 * 1.33686; its five M1 bars (spreads 42-48) gave 1.33688. The engine, warming both streams through
 * one aggregator, built 1.33688 - and the materializer refused the whole capture.
 */
class WarmupBuiltCandleConflictTest {
    private val start = 1_790_026_200_000L
    private val end = start + 300_000L

    private fun bar(high: String) =
        Candle(
            "EXNESS:GBPUSD",
            BigDecimal("1.33681"),
            BigDecimal(high),
            BigDecimal("1.33678"),
            BigDecimal("1.33684"),
            BigDecimal.ONE,
            start,
            end,
        )

    private fun warmupTicksOf(bar: Candle) =
        listOf(bar.open, bar.high, bar.low, bar.close).mapIndexed { i, price ->
            RecordedTick(
                i.toLong(),
                warmup = true,
                sourceTimeframeMs = 300_000L,
                tick =
                    Tick(
                        bar.symbol,
                        price,
                        start + i * 60_000L,
                    ),
            )
        }

    private fun capture(
        engineBar: Candle,
        firstLiveTickMs: Long,
    ) = GoldenMarketCapture(
        manifest = JsonObject(emptyMap()),
        ticks =
            warmupTicksOf(bar("1.33686")) +
                RecordedTick(
                    99L,
                    warmup = false,
                    sourceTimeframeMs = null,
                    Tick(engineBar.symbol, BigDecimal("1.33690"), firstLiveTickMs),
                ),
        candles = listOf(RecordedCandle(50L, engineBar, CAPTURED_CANDLE_EVENT)),
        streamCandles = emptyList(),
        strategyCandleEvaluations = 0L,
    )

    @Test
    fun `a bar the engine built from warmup alone yields to the stream's own venue bar`() {
        val bars = replayCandles(capture(bar("1.33688"), firstLiveTickMs = end + 58L))

        val replayed = bars.single { it.candle.startTime == start && it.candle.endTime == end }
        assertThat(replayed.provenance).isEqualTo(REHYDRATED_WARMUP_TICKS)
        assertThat(replayed.candle.high).isEqualByComparingTo("1.33686")
    }

    @Test
    fun `a bar the engine built from live ticks that disagrees still fails the capture closed`() {
        assertThatThrownBy { replayCandles(capture(bar("1.33688"), firstLiveTickMs = start + 30_000L)) }
            .hasMessageContaining("conflicting golden candles")
    }
}
