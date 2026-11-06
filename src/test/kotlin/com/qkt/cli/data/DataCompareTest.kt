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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataCompareTest {
    @TempDir
    lateinit var dataRoot: Path

    private val day = LocalDate.parse("2024-01-04")

    private fun seed(
        symbol: String,
        offset: Double = 0.0,
    ) {
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ticks =
            (0 until 120).map { m ->
                Tick(symbol, Money.of("%.2f".format(1850.0 + offset + (m % 7))), start + m * 60_000L)
            }
        val file = dataRoot.resolve("symbols/$symbol/$day.bin")
        Files.createDirectories(file.parent)
        BinaryTickWriter().write(file, symbol, ticks)
    }

    private fun compare(vararg extra: String) =
        captureCliRun {
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "compare",
                        "GOLDA",
                        "--against",
                        "GOLDB",
                        "--from",
                        "$day",
                        "--to",
                        "$day",
                        "--data-root",
                        dataRoot.toString(),
                        *extra,
                    ),
                ),
            ).run()
        }

    @Test
    fun `two copies of one series are identical`() {
        seed("GOLDA")
        seed("GOLDB")

        val run = compare()

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.lines()[0]).isEqualTo("qkt data compare: GOLDA vs GOLDB 1m 2024-01-04 … 2024-01-04")
        assertThat(
            run.out.lines()[1],
        ).startsWith("  identical: ").contains("bars matched, 0 only in the first, 0 only in the second")
    }

    @Test
    fun `a shifted price series differs and json reports the counts`() {
        seed("GOLDA")
        seed("GOLDB", offset = 50.0)

        val run = compare("--tf", "5m", "--json")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out.trim())
            .startsWith("{\"first\":\"GOLDA\",\"second\":\"GOLDB\",\"verdict\":\"differs\",\"matched\":")
            .contains("\"offsetMs\":")
            .contains("\"summary\":\"differs: ")
    }

    @Test
    fun `a series missing from one side has no overlap`() {
        seed("GOLDA")

        val run = compare()

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out).contains("no overlap: 0 bars matched, ")
    }

    @Test
    fun `missing flags print the usage`() {
        val run = captureCliRun { DataCommand(Args(arrayOf("data", "compare", "GOLDA", "--from", "$day"))).run() }

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("qkt: usage: qkt data compare <SYMBOL> --against <SYMBOL>")
    }
}
