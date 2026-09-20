package com.qkt.backtest

import com.qkt.backtest.TickResolvedMultiSymbolFixtures.bothTradeStrategy
import com.qkt.backtest.TickResolvedMultiSymbolFixtures.seedSym
import com.qkt.backtest.TickResolvedMultiSymbolFixtures.twoSymbolStrategy
import com.qkt.backtest.TickResolvedParityFixtures.field
import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TickResolvedMultiSymbolParityTest {
    @Test
    fun `tick-resolved fills emit the same trades in the same order for a two-symbol strategy`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedSym(dataRoot, "XAUUSD", 5, 1850.0, 30.0, 7.0)
        seedSym(dataRoot, "XAGUSD", 5, 24.0, 0.4, 5.0)
        for (sym in listOf("XAUUSD", "XAGUSD")) {
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        sym,
                        "--tf",
                        "15m",
                        "--from",
                        from,
                        "--to",
                        to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run().also { assertThat(it).isEqualTo(ExitCodes.SUCCESS) }
        }

        fun trades(
            reportDir: Path,
            extra: List<String>,
        ): List<String> {
            val out = ByteArrayOutputStream()
            val orig = System.out
            val code =
                try {
                    System.setOut(PrintStream(out))
                    BacktestCommand(
                        Args(
                            (
                                listOf(
                                    "backtest",
                                    bothTradeStrategy(dir).toString(),
                                    "--from",
                                    from,
                                    "--to",
                                    to,
                                    "--data-root",
                                    dataRoot.toString(),
                                    "--no-fetch",
                                    "--allow-incomplete",
                                    "--json",
                                    "--report-dir",
                                    reportDir.toString(),
                                ) + extra
                            ).toTypedArray(),
                        ),
                    ).run()
                } finally {
                    System.setOut(orig)
                }
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            // trades.csv: timestamp+symbol columns are enough to assert order (col 0 ts, col 2 symbol)
            return Files.readAllLines(reportDir.resolve("trades.csv")).drop(1).map {
                val c = it.split(",")
                "${c[0]},${c[2]},${c[3]}"
            }
        }

        val ft = trades(dir.resolve("ft"), emptyList())
        val tf = trades(dir.resolve("tf"), listOf("--bars", "--tick-fills"))
        assertThat(ft).isNotEmpty
        assertThat(tf).isEqualTo(ft) // same trades, same chronological order
    }

    @Test
    fun `tick-resolved fills match a full-tick replay for a multi-symbol strategy`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedSym(dataRoot, "XAUUSD", 5, 1850.0, 30.0, 7.0)
        seedSym(dataRoot, "XAGUSD", 5, 24.0, 0.4, 5.0) // different path + period -> cross-symbol ordering matters
        for (sym in listOf("XAUUSD", "XAGUSD")) {
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        sym,
                        "--tf",
                        "15m",
                        "--from",
                        from,
                        "--to",
                        to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run().also { assertThat(it).isEqualTo(ExitCodes.SUCCESS) }
        }

        fun run(extra: List<String>): String {
            val out = ByteArrayOutputStream()
            val orig = System.out
            val code =
                try {
                    System.setOut(PrintStream(out))
                    BacktestCommand(
                        Args(
                            (
                                listOf(
                                    "backtest",
                                    twoSymbolStrategy(dir).toString(),
                                    "--from",
                                    from,
                                    "--to",
                                    to,
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
                    System.setOut(orig)
                }
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            return out.toString()
        }

        val fullTick = run(emptyList())
        val resolved = run(listOf("--bars", "--tick-fills"))
        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        assertThat(field(resolved, "trades")).isEqualTo(field(fullTick, "trades"))
        assertThat(field(resolved, "totalPnL")).isEqualTo(field(fullTick, "totalPnL"))
    }
}
