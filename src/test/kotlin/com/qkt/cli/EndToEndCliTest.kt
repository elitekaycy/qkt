package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndCliTest {
    private fun invoke(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = runMain(argv as Array<String>)
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `--version prints qkt version`() {
        val (code, stdout, _) = invoke("--version")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout.trim()).isEqualTo("qkt ${BuildInfo.VERSION}")
    }

    @Test
    fun `--help prints usage`() {
        val (code, stdout, _) = invoke("--help")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("USAGE")
        assertThat(stdout).contains("parse")
        assertThat(stdout).contains("backtest")
        assertThat(stdout).contains("run")
    }

    @Test
    fun `parse on valid strategy exits 0`() {
        val (code, stdout, _) = invoke("parse", "src/test/resources/cli/valid_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("ok")
    }

    @Test
    fun `parse on broken strategy exits 1`() {
        val (code, _, stderr) = invoke("parse", "src/test/resources/cli/broken_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("broken_strategy.qkt:")
    }

    @Test
    fun `backtest produces report on fixture data`() {
        val (code, stdout, stderr) =
            invoke(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("Trades:")
    }

    @Test
    fun `run with rejected --source bybit returns user error`() {
        val (code, _, stderr) =
            invoke(
                "run",
                "src/test/resources/cli/valid_strategy.qkt",
                "--source",
                "bybit",
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("not yet enabled in 12a")
    }

    @Test
    fun `unknown subcommand returns arg error`() {
        val (code, _, stderr) = invoke("frobnicate")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("unknown subcommand")
    }

    @Test
    fun `missing required flag surfaces arg error via Main`() {
        val (code, _, stderr) =
            invoke(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("--from")
    }
}
