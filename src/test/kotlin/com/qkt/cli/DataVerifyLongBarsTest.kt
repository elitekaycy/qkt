package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Bars of a timeframe over a day keep one bar per several day files, so only the file checks apply to them. */
class DataVerifyLongBarsTest {
    @Test
    fun `two day bars pass on the file checks alone and an empty file is flagged`(
        @TempDir root: Path,
    ) {
        val dir = Files.createDirectories(root.resolve("bars/MYBROKER/EURUSD/2d"))
        var day = LocalDate.of(2026, 10, 12)
        if (day.toEpochDay() % 2 != 0L) day = day.plusDays(1)
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        Files.writeString(
            dir.resolve("$day.csv"),
            "timestamp,open,high,low,close,volume\n$start,1.1,1.2,1.0,1.1,5\n",
        )
        Files.writeString(dir.resolve("${day.plusDays(1)}.csv"), "timestamp,open,high,low,close,volume\n")

        val run =
            captureCliRun {
                DataCommand(Args(arrayOf("data", "verify", "MYBROKER:EURUSD", "--data-root", root.toString()))).run()
            }

        assertThat(run.out.lines()).anyMatch { it.contains("$day") && it.endsWith("ok") }
        assertThat(run.out.lines()).anyMatch { it.contains("${day.plusDays(1)}") && it.endsWith("empty") }
        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
