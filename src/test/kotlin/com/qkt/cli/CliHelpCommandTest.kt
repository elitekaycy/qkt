package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CliHelpCommandTest {
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
    fun `mistyped command suggests and shows that command's help`() {
        val (code, _, stderr) = invoke("backtes")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("Did you mean 'qkt backtest'?")
        assertThat(stderr).contains("Usage: qkt backtest <strategy.qkt>")
        assertThat(stderr).contains("--from")
    }

    @Test
    fun `no close match lists the available commands`() {
        val (code, _, stderr) = invoke("frobnicate")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("USAGE")
        assertThat(stderr).contains("backtest <file>")
    }

    @Test
    fun `unknown flag suggests and shows the command flags`() {
        val (code, _, stderr) = invoke("backtest", "s.qkt", "--frm", "2025-09-01")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("unknown flag --frm. Did you mean --from?")
        assertThat(stderr).contains("Usage: qkt backtest <strategy.qkt>")
    }

    @Test
    fun `missing argument prints usage and flags`() {
        val (code, _, stderr) = invoke("backtest")
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("missing required argument: <strategy.qkt>")
        assertThat(stderr).contains("Usage: qkt backtest <strategy.qkt>")
        assertThat(stderr).contains("--from")
    }

    @Test
    fun `per-command help shows the same text`() {
        val (code, stdout, _) = invoke("backtest", "--help")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("Usage: qkt backtest <strategy.qkt>")
        assertThat(stdout).contains("--from")
        assertThat(stdout).contains("window start")
    }

    @Test
    fun `unknown run exits arg error with a one-line message`() {
        val dir = Files.createTempDirectory("qkt-run-cfg")
        dir.toFile().deleteOnExit()
        val cfg = dir.resolve("qkt.config.yaml")
        Files.writeString(cfg, "backtest:\n  runs:\n    late:\n      strategy: s.qkt\n")
        val (code, _, stderr) = invoke("backtest", "latee", "--config", cfg.toString())
        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("no file 'latee' and no run 'latee'")
        assertThat(stderr).doesNotContain("at com.qkt")
    }

    @Test
    fun `help all shows every flag and help word narrows`() {
        val (fullCode, full, _) = invoke("backtest", "--help", "--all")
        assertThat(fullCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(full).contains("--seed")
        assertThat(full).contains("--no-report")

        val (oneCode, one, _) = invoke("backtest", "--help", "from")
        assertThat(oneCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(one).contains("--from")
        assertThat(one).doesNotContain("--broker")
    }
}
