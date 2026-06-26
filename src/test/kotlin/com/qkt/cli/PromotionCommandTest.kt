package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PromotionCommandTest {
    private fun invoke(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = PromotionCommand(Args(argv as Array<String>)).run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `approve records production state approval evidence and paper metrics`(
        @TempDir tmp: Path,
    ) {
        val strategy = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val registry = tmp.resolve("promotion")

        val (code, stdout, stderr) =
            invoke(
                "promotion",
                "approve",
                strategy.toString(),
                "--as",
                "alpha",
                "--state",
                "production",
                "--reason",
                "walk-forward and paper passed",
                "--evidence",
                "dataset_snapshot=snap-001",
                "--evidence",
                "realistic_execution=mt5-realistic",
                "--paper-days",
                "21",
                "--paper-trades",
                "144",
                "--p95-slippage-bps",
                "2.5",
                "--registry-dir",
                registry.toString(),
                "--json",
            )

        assertThat(code).withFailMessage(stderr).isEqualTo(ExitCodes.SUCCESS)
        val record = PromotionStore(registry).latest("alpha")
        assertThat(record?.state).isEqualTo(PromotionState.PRODUCTION)
        assertThat(record?.approvals).hasSize(1)
        assertThat(record?.evidence).containsEntry("dataset_snapshot", "snap-001")
        assertThat(record?.paper?.days).isEqualTo(21)
        assertThat(stdout).contains("\"strategy\":\"alpha\"")
    }

    @Test
    fun `status evaluates configured gates against a recorded strategy hash`(
        @TempDir tmp: Path,
    ) {
        val strategy = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val registry = tmp.resolve("promotion")
        val config =
            tmp.resolve("qkt.config.yaml").also {
                Files.writeString(
                    it,
                    """
                    promotion:
                      enforce: true
                      required_state: production
                      dataset_snapshot: true
                      realistic_execution: true
                      paper_days: 10
                      paper_min_trades: 50
                      max_paper_slippage_bps: 5
                    """.trimIndent(),
                )
            }
        invoke(
            "promotion",
            "approve",
            strategy.toString(),
            "--as",
            "alpha",
            "--state",
            "production",
            "--reason",
            "ready",
            "--evidence",
            "dataset_snapshot=snap-001,realistic_execution=mt5-realistic",
            "--paper-days",
            "12",
            "--paper-trades",
            "80",
            "--p95-slippage-bps",
            "3",
            "--registry-dir",
            registry.toString(),
        )

        val (code, stdout, stderr) =
            invoke(
                "promotion",
                "status",
                "alpha",
                "--strategy",
                strategy.toString(),
                "--config",
                config.toString(),
                "--registry-dir",
                registry.toString(),
                "--json",
            )

        assertThat(code).withFailMessage(stderr).isEqualTo(ExitCodes.SUCCESS)
        val result = Json.parseToJsonElement(stdout).jsonObject
        assertThat(result["eligibleForProduction"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(result["missingGates"].toString()).isEqualTo("[]")
        assertThat(result["paper"].toString()).contains("\"days\":12")

        val (textCode, textOut, textErr) =
            invoke(
                "promotion",
                "status",
                "alpha",
                "--strategy",
                strategy.toString(),
                "--config",
                config.toString(),
                "--registry-dir",
                registry.toString(),
            )
        assertThat(textCode).withFailMessage(textErr).isEqualTo(ExitCodes.SUCCESS)
        assertThat(textOut).contains("paper/live validation: 12 days, 80 trades")
        assertThat(textOut).contains("p95_slippage_bps=3.0")
    }

    @Test
    fun `waiver requires a reason`(
        @TempDir tmp: Path,
    ) {
        val strategy = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }

        assertThatThrownBy {
            PromotionCommand(
                Args(
                    arrayOf(
                        "promotion",
                        "waive",
                        strategy.toString(),
                        "--as",
                        "alpha",
                        "--gate",
                        "operator_approval",
                        "--registry-dir",
                        tmp.resolve("promotion").toString(),
                    ),
                ),
            ).run()
        }.isInstanceOf(ArgError::class.java)
            .hasMessageContaining("missing required flag --reason")
    }
}
