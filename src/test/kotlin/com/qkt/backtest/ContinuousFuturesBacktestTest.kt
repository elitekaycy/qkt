package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyToRecursively
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A continuous futures stream backtested on real Binance data across the 2024-09-19 08:00 roll
 * (`src/test/resources/futures/btcusdt-rolls`, see its PROVENANCE.md).
 */
class ContinuousFuturesBacktestTest {
    private fun dataRoot(dir: Path): Path {
        val fixture = Paths.get(requireNotNull(javaClass.getResource("/futures/btcusdt-rolls")).toURI())
        val root = dir.resolve("data")
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fixture.copyToRecursively(root, followLinks = false, overwrite = false)
        return root
    }

    private fun backtest(
        dir: Path,
        timeframe: String,
        extra: List<String> = emptyList(),
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY cont VERSION 1
            SYMBOLS
                btc = BINANCE_UM:BTCUSDT@front EVERY $timeframe
            RULES
                WHEN ema(btc.close, 3) CROSSES ABOVE ema(btc.close, 9)
                THEN BUY btc SIZING 0.01
                WHEN ema(btc.close, 3) CROSSES BELOW ema(btc.close, 9)
                THEN CLOSE btc
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(out))
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategy.toString(),
                            "--from",
                            "2024-09-17",
                            "--to",
                            "2024-09-21",
                            "--data-root",
                            dataRoot(dir).toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--json",
                            *extra.toTypedArray(),
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to out.toString()
    }

    @Test
    fun `a front stream backtests across a roll without a gap or a duplicate bar`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, "15m")
        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        val report = Json.parseToJsonElement(output.lineSequence().single { it.trim().startsWith("{") }).jsonObject
        assertThat(report.getValue("trades").jsonPrimitive.int).isGreaterThan(0)
        val candles =
            report
                .getValue("inputSummary")
                .jsonObject
                .getValue("streamCandles")
                .jsonObject
        assertThat(
            candles.values
                .single()
                .jsonPrimitive.int,
        ).isEqualTo(4 * 96)
    }

    @Test
    fun `a daily stream whose bars would span the 08-00 roll is refused`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, "1d")
        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("2024-09-19T08:00:00Z").contains("roll")
    }

    @Test
    fun `tick-resolved fills are refused for a continuous stream`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, "15m", listOf("--bars", "--tick-fills"))
        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("--tick-fills").contains("continuous")
    }

    @Test
    fun `a futures run explains its exchange fills instead of the paper broker's`(
        @TempDir dir: Path,
    ) {
        val (code, output) = backtest(dir, "15m")
        assertThat(code).describedAs(output).isEqualTo(ExitCodes.SUCCESS)
        assertThat(output).doesNotContain("paper broker fills at mid")
        assertThat(output).contains("BINANCE_UM:BTCUSDT@front fills on the exchange simulator")
    }
}
