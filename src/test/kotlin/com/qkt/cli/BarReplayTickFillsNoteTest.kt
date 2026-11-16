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
    ) = BacktestBarReplay.resolveBarReplay(
        args,
        data.toString(),
        from,
        to,
        listOf(stream.qktSymbol),
        listOf(stream),
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
    fun `bars with execution latency fall back to bar fills and say so`(
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

        val run =
            captureCliRun {
                val config =
                    resolve(
                        Args(arrayOf("backtest", "s.qkt", "--bars")),
                        data,
                        ExecutionSimulationConfig(latencyMs = 100),
                    )
                if (config.tickFills) 1 else 0
            }

        assertThat(run.code).withFailMessage(run.err).isZero()
        assertThat(
            run.err,
        ).contains("qkt: note: tick-resolved fills are off for this run: execution latency or order spacing")
    }
}
