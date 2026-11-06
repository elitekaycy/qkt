package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataSnapshotVerifyUsageTest {
    @TempDir
    lateinit var root: Path

    private fun data(vararg argv: String) =
        captureCliRun { DataCommand(Args(arrayOf("data", *argv, "--data-root", root.toString()))).run() }

    @Test
    fun `snapshot needs a symbol a range and an output`() {
        val noSymbol = data("snapshot")
        assertThat(noSymbol.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noSymbol.err).contains("qkt: missing symbol. usage: qkt data snapshot <symbol>")

        val noOut = data("snapshot", "XAUUSD", "--from", "2024-01-04", "--to", "2024-01-05")
        assertThat(noOut.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noOut.err).contains("qkt: data snapshot requires --from, --to, and --out")
    }

    @Test
    fun `a snapshot of days that are not cached is a user error`() {
        val run =
            data(
                "snapshot",
                "XAUUSD",
                "--from",
                "2024-01-04",
                "--to",
                "2024-01-05",
                "--out",
                root.resolve("s.json").toString(),
            )

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).startsWith("qkt: error: ")
        assertThat(root.resolve("s.json")).doesNotExist()
    }

    @Test
    fun `verify needs a symbol with day files`() {
        val noSymbol = data("verify")
        assertThat(noSymbol.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noSymbol.err).contains("qkt data verify --snapshot <file> [--strict]")

        Files.createDirectories(root.resolve("symbols/XAUUSD"))
        Files.writeString(root.resolve("symbols/XAUUSD/notes.txt"), "not a day file")
        val empty = data("verify", "XAUUSD")
        assertThat(empty.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(empty.err).contains("qkt: no day files for 'XAUUSD' under")
    }

    @Test
    fun `an unreadable snapshot file is reported`() {
        val bad = root.resolve("bad.json").also { Files.writeString(it, "{not json") }

        val run = data("verify", "--snapshot", bad.toString())

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: error: cannot read snapshot $bad:")
    }

    @Test
    fun `build-bars needs a symbol a valid timeframe and cached ticks`() {
        val noSymbol = data("build-bars")
        assertThat(noSymbol.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noSymbol.err).contains("qkt: missing symbol. usage: qkt data build-bars <symbol>")

        val noTf = data("build-bars", "XAUUSD")
        assertThat(noTf.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noTf.err).contains("qkt: missing --tf (e.g. --tf 15m)")

        val badTf = data("build-bars", "XAUUSD", "--tf", "0m")
        assertThat(badTf.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(badTf.err).startsWith("qkt: error: ")

        val noTicks = data("build-bars", "XAUUSD", "--tf", "15m")
        assertThat(noTicks.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noTicks.err).contains("qkt: no cached tick data for 'XAUUSD'")
    }
}
