package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestCommandTest {
    private fun runBacktest(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = BacktestCommand(Args(argv as Array<String>)).run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `produces text report from fixture data`() {
        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("Trades:")
        assertThat(stdout).contains("Final realized:")
        assertThat(stdout).contains("Max drawdown:")
    }

    @Test
    fun `missing required from flag throws ArgError`() {
        assertThatThrownBy {
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        }.isInstanceOf(ArgError::class.java)
            .hasMessageContaining("--from")
    }

    @Test
    fun `produces parseable JSON report with --json`() {
        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
                "--json",
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val payload = stdout.trim().lines().last()
        val obj = Json.parseToJsonElement(payload) as JsonObject
        assertThat(obj["trades"]?.jsonPrimitive?.intOrNull).isNotNull
        assertThat(obj["finalRealized"]).isNotNull
        assertThat(obj["finalUnrealized"]).isNotNull
        assertThat(obj["totalPnL"]).isNotNull
        assertThat(obj["winRate"]).isNotNull
        assertThat(obj["maxDrawdown"]).isNotNull
        assertThat(obj["maxConsecutiveLosses"]?.jsonPrimitive?.intOrNull).isNotNull
        assertThat(obj["cadence"]?.jsonPrimitive?.contentOrNull).isNotNull
    }

    @Test
    fun `missing strategy file exits with user error`() {
        val (code, _, stderr) =
            runBacktest(
                "backtest",
                "does_not_exist.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("file not found")
    }
}
