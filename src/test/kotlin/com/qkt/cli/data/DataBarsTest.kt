package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataBarsTest {
    @TempDir
    lateinit var dataRoot: Path

    private val day = LocalDate.parse("2024-01-04")

    private fun seedDay() {
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ticks =
            (0 until 1440).map { m ->
                Tick(
                    "XAUUSD",
                    Money.of("%.3f".format(1850.0 + 5.0 * sin(m / 50.0))),
                    start + m * 60_000L,
                )
            }
        val file = dataRoot.resolve("symbols/XAUUSD/$day.bin")
        Files.createDirectories(file.parent)
        BinaryTickWriter().write(file, "XAUUSD", ticks)
    }

    private fun data(vararg argv: String) =
        captureCliRun { DataCommand(Args(arrayOf("data", *argv, "--data-root", dataRoot.toString()))).run() }

    private fun bars(vararg extra: String) = data("bars", "XAUUSD", "--from", "$day", "--to", "$day", *extra)

    @Test
    fun `bars built from ticks print as csv with a count summary on stderr`() {
        seedDay()

        val run = bars("--tf", "1h")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        val lines = run.out.trim().lines()
        assertThat(lines.first()).isEqualTo("time,timestamp,open,high,low,close,volume")
        assertThat(lines).hasSize(25)
        assertThat(lines[1]).startsWith("2024-01-04T00:00:00Z,1704326400000,1850")
        assertThat(run.err).contains("qkt data bars: XAUUSD 1h 2024-01-04 … 2024-01-04 — 24 bars")
    }

    @Test
    fun `the bars tier rolls the coarsest built divisor up to the asked timeframe`() {
        seedDay()
        assertThat(data("build-bars", "XAUUSD", "--tf", "15m").code).isEqualTo(ExitCodes.SUCCESS)
        val fromTicks = bars("--tf", "1h").out

        val rolled = bars("--tf", "1h", "--bars")

        assertThat(rolled.code).withFailMessage(rolled.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(rolled.err).contains("rolled up from built 15m bars")
        assertThat(rolled.out.trim().lines()).hasSize(25)
        assertThat(
            rolled.out
                .lines()[1]
                .split(",")
                .take(4),
        ).isEqualTo(fromTicks.lines()[1].split(",").take(4))

        val exact = bars("--tf", "15m", "--bars")
        assertThat(exact.err).doesNotContain("rolled up")
        assertThat(exact.out.trim().lines()).hasSize(97)
    }

    @Test
    fun `the bars tier with nothing built reads no bars even when ticks exist`() {
        seedDay()

        val run = bars("--tf", "1h", "--bars")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).doesNotContain("rolled up").contains("qkt: no 1h bars for XAUUSD")
    }

    @Test
    fun `an empty range exits one naming the window`() {
        val run = bars("--tf", "1h")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out.trim()).isEqualTo("time,timestamp,open,high,low,close,volume")
        assertThat(run.err).contains("qkt: no 1h bars for XAUUSD between 2024-01-04 and 2024-01-04")
    }

    @Test
    fun `missing flags print the usage and a bad timeframe is a user error`() {
        val missing = data("bars", "XAUUSD", "--tf", "1h")
        assertThat(missing.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(missing.err).contains("qkt: usage: qkt data bars")

        val badTf = bars("--tf", "0m")
        assertThat(badTf.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(badTf.err).startsWith("qkt: error: ")
    }
}
