package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * An imported series can say which market calendar it trades on (`--calendar nyse`), and coverage then counts that
 * market's holidays as closed days, not missing data: a US stock's daily bars have no row on Juneteenth.
 */
class ImportCalendarTest {
    private fun quiet(block: () -> Int): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val (out, original, originalErr) = Triple(ByteArrayOutputStream(), System.out, System.err)
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            block() to (out.toString() + err.toString())
        } finally {
            System.setOut(original)
            System.setErr(originalErr)
        }
    }

    /** Weekday daily bars for June 2026 except Juneteenth (Friday 2026-06-19), as a US equity source writes them. */
    private fun dailyCsv(dir: Path): Path {
        val rows =
            generateSequence(LocalDate.of(2026, 6, 1)) { it.plusDays(1) }
                .takeWhile { it.isBefore(LocalDate.of(2026, 7, 1)) }
                .filter { it.dayOfWeek.value <= 5 && it != LocalDate.of(2026, 6, 19) }
                .joinToString("\n", "date,open,high,low,close,volume\n") { "$it,100,101,99,100.5,1000" }
        return dir.resolve("daily.csv").also { Files.writeString(it, rows) }
    }

    private fun import(
        root: Path,
        csv: Path,
        vararg extra: String,
    ) = quiet {
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "import",
                    csv.toString(),
                    "--as",
                    "MYFEED:SPY",
                    "--kind",
                    "bars",
                    "--tz",
                    "UTC",
                    "--tf",
                    "1d",
                    "--map",
                    "time=date",
                    "--price",
                    "last",
                    "--data-root",
                    root.toString(),
                    *extra,
                ),
            ),
        ).run()
    }

    private fun backtest(
        dir: Path,
        root: Path,
    ): Pair<Int, String> {
        val strategy = dir.resolve("s.qkt")
        Files.writeString(
            strategy,
            "STRATEGY s VERSION 1\nSYMBOLS\n    spy = MYFEED:SPY EVERY 1d\nRULES\n    WHEN spy.close > 0 AND " +
                "POSITION.spy = 0\n    THEN BUY spy SIZING 1\n",
        )
        return quiet {
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategy.toString(),
                        "--from",
                        "2026-06-01",
                        "--to",
                        "2026-07-01",
                        "--bars",
                        "--no-fetch",
                        "--data-root",
                        root.toString(),
                    ),
                ),
            ).run()
        }
    }

    @Test
    fun `a holiday is a closed day for a series imported on its market's calendar`(
        @TempDir dir: Path,
    ) {
        val root = dir.resolve("data")
        val (imported, importText) = import(root, dailyCsv(dir), "--calendar", "nyse")
        assertThat(imported).withFailMessage(importText).isEqualTo(ExitCodes.SUCCESS)

        val (code, text) = backtest(dir, root)

        assertThat(code).withFailMessage(text).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("bar coverage MYFEED:SPY 21/21 trading days")
    }

    @Test
    fun `without a stated calendar the holiday still reads as missing, and the error says how to state one`(
        @TempDir dir: Path,
    ) {
        val root = dir.resolve("data")
        import(root, dailyCsv(dir))

        val (code, text) = backtest(dir, root)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(text).contains("missing 2026-06-19").contains("--calendar")
    }

    @Test
    fun `an unknown calendar is refused with the names qkt knows`(
        @TempDir dir: Path,
    ) {
        val (code, text) = import(dir.resolve("data"), dailyCsv(dir), "--calendar", "moon")

        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(text).contains("crypto, fx, nyse, cme_globex")
    }
}
