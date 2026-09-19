package com.qkt.backtest

import com.qkt.backtest.TickResolvedParityFixtures.field
import com.qkt.backtest.TickResolvedParityFixtures.normalizedReport
import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TickResolvedRealDataParityTest {
    private fun seedRealEurUsdDay(dataRoot: Path) {
        val target = dataRoot.resolve("symbols/EURUSD/2024-01-10.csv.gz")
        Files.createDirectories(target.parent)
        requireNotNull(javaClass.getResourceAsStream("/parity/dukascopy/eurusd-2024-01-10.csv.gz")) {
            "real-data parity fixture is missing"
        }.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun realDataStrategyFile(dir: Path): Path {
        val strategy = dir.resolve("real-eurusd.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY real_eurusd VERSION 1
            SYMBOLS
                eurusd = BACKTEST:EURUSD EVERY 5m
            RULES
                WHEN ema(eurusd.close, 3) CROSSES ABOVE ema(eurusd.close, 9)
                THEN BUY eurusd SIZING 0.1 BRACKET { STOP LOSS PCT 0.1, TAKE PROFIT RR 2 }
                WHEN ema(eurusd.close, 3) CROSSES BELOW ema(eurusd.close, 9)
                THEN CLOSE eurusd
            """.trimIndent(),
        )
        return strategy
    }

    private fun runRealDataJson(
        strategy: Path,
        dataRoot: Path,
        extra: List<String>,
    ): String {
        val output = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(output))
                BacktestCommand(
                    Args(
                        (
                            listOf(
                                "backtest",
                                strategy.toString(),
                                "--from",
                                "2024-01-10",
                                "--to",
                                "2024-01-11",
                                "--data-root",
                                dataRoot.toString(),
                                "--no-fetch",
                                "--allow-incomplete",
                                "--json",
                            ) + extra
                        ).toTypedArray(),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        return output.toString()
    }

    @Test
    fun `tick-resolved fills match full-tick replay across a real EURUSD day`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedRealEurUsdDay(dataRoot)
        val build =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "EURUSD",
                        "--tf",
                        "5m",
                        "--from",
                        "2024-01-10",
                        "--to",
                        "2024-01-11",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)

        val strategy = realDataStrategyFile(dir)
        val fullTick = runRealDataJson(strategy, dataRoot, extra = emptyList())
        val resolved = runRealDataJson(strategy, dataRoot, extra = listOf("--bars", "--tick-fills"))

        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        assertThat(normalizedReport(resolved)).isEqualTo(normalizedReport(fullTick))
    }
}
