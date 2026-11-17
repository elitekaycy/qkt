package com.qkt.cli.golden

import com.qkt.common.FixedClock
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenReplayEdgesTest {
    private fun candle(
        start: Long,
        end: Long,
    ) = Candle(
        "EXNESS:GBPUSD",
        BigDecimal("1.3"),
        BigDecimal("1.4"),
        BigDecimal("1.2"),
        BigDecimal("1.3"),
        BigDecimal.ONE,
        start,
        end,
    )

    @Test
    fun `a bar from a source this reader does not know loses to a known one for the same bar`() {
        val bar = candle(0L, 60_000L)
        val capture =
            GoldenMarketCapture(
                manifest = JsonObject(emptyMap()),
                ticks = emptyList(),
                candles = listOf(RecordedCandle(1, bar, "MYSTERY"), RecordedCandle(2, bar, CAPTURED_CANDLE_EVENT)),
                streamCandles = emptyList(),
                strategyCandleEvaluations = 0L,
            )

        val merged = replayCandles(capture)

        assertThat(merged).containsExactly(RecordedCandle(2, bar, CAPTURED_CANDLE_EVENT))
    }

    @Test
    fun `bars whose window is not a whole number of seconds cannot be stored`(
        @TempDir root: Path,
    ) {
        val writer = GoldenReplayStoreWriter(FixedClock(1_754_740_800_000L))

        assertThatThrownBy {
            writer.writeBarStores(
                root,
                listOf(RecordedCandle(1, candle(0L, 500L), CAPTURED_CANDLE_EVENT)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("unsupported sub-second candle window: 500ms")
    }
}
