package com.qkt.cli

import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.StreamDecl
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarReplayTickFillsNoteTest : BarsReplayFixture() {
    private val stream = StreamDecl("gold", "BACKTEST", "XAUUSD", "15m")
    private val from = Instant.parse("2024-01-02T00:00:00Z")
    private val to = Instant.parse("2024-01-03T00:00:00Z")

    private fun resolve(
        args: Args,
        data: Path,
        config: ExecutionSimulationConfig,
        streams: List<StreamDecl> = listOf(stream),
    ) = BacktestBarReplay.resolveBarReplay(
        args,
        data.toString(),
        from,
        to,
        listOf(stream.qktSymbol),
        streams,
        TimeWindow.parse("15m"),
        config,
    )

    @Test
    fun `explicit tick fills without bars are refused`(
        @TempDir data: Path,
    ) {
        assertThatThrownBy {
            resolve(
                Args(arrayOf("backtest", "s.qkt", "--tick-fills")),
                data,
                ExecutionSimulationConfig(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("--tick-fills requires --bars (bars drive signals; ticks resolve fills)")
    }

    @Test
    fun `bars over mixed timeframes fall back to bar fills and say so`(
        @TempDir data: Path,
    ) {
        seedTicks(data, 1)
        val built =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
                        "--tf",
                        "15m",
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-02",
                        "--data-root",
                        data.toString(),
                    ),
                ),
            ).run()
        assertThat(built).isEqualTo(ExitCodes.SUCCESS)

        val mixed = listOf(stream, StreamDecl("slow", "BACKTEST", "XAUUSD", "1h"))

        // The note is printed before the finer coverage checks, which this fixture does not satisfy for 1h.
        val run =
            captureCliRun {
                runCatching {
                    resolve(Args(arrayOf("backtest", "s.qkt", "--bars")), data, ExecutionSimulationConfig(), mixed)
                }
                0
            }

        assertThat(
            run.err,
        ).contains("qkt: note: tick-resolved fills are off for this run: mixed-timeframe strategies are not supported")
    }
}
