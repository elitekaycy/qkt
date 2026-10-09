package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestRunsE2ETest : BacktestCommandFixture() {
    private fun project(
        dir: Path,
        backtest: String,
    ): Path {
        Files.copy(
            Path.of("src/test/resources/cli/valid_strategy.qkt"),
            dir.resolve("strat.qkt"),
        )
        val cfg = dir.resolve("qkt.config.yaml")
        Files.writeString(cfg, "starting_balance: 10000\n$backtest")
        return cfg
    }

    private fun json(stdout: String): JsonObject = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject

    @Test
    fun `bare backtest runs the global block and records provenance`(
        @TempDir home: Path,
        @TempDir proj: Path,
    ) {
        val cfg =
            project(
                proj,
                """
                backtest:
                  strategy: ${proj.resolve("strat.qkt")}
                  from: 2024-01-15
                  to: 2024-01-16
                  broker: paper
                """.trimIndent(),
            )
        val (code, stdout, stderr) =
            runBacktestWithRunsRoot(
                home,
                "backtest",
                "--config",
                cfg.toString(),
                "--data-root",
                "src/test/resources/cli/data",
                "--allow-incomplete",
                "--json",
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        val resolved = json(stdout)["evidence"]!!.jsonObject["resolved"]!!.jsonObject
        assertThat(resolved["from"]!!.jsonObject["from"]!!.jsonPrimitive.content).isEqualTo("global")
        assertThat(resolved["broker"]!!.jsonObject["from"]!!.jsonPrimitive.content).isEqualTo("global")
        assertThat(resolved["strategy"]!!.jsonObject["from"]!!.jsonPrimitive.content).isEqualTo("global")
    }

    @Test
    fun `a named run inherits global keys and flags win`(
        @TempDir home: Path,
        @TempDir proj: Path,
    ) {
        val cfg =
            project(
                proj,
                """
                backtest:
                  strategy: ${proj.resolve("strat.qkt")}
                  from: 2024-01-15
                  to: 2024-01-16
                  broker: paper
                  runs:
                    late:
                      from: 2024-01-15
                """.trimIndent(),
            )
        val (code, stdout, stderr) =
            runBacktestWithRunsRoot(
                home,
                "backtest",
                "late",
                "--config",
                cfg.toString(),
                "--data-root",
                "src/test/resources/cli/data",
                "--allow-incomplete",
                "--json",
                "--broker",
                "paper",
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        val resolved = json(stdout)["evidence"]!!.jsonObject["resolved"]!!.jsonObject
        assertThat(resolved["from"]!!.jsonObject["from"]!!.jsonPrimitive.content).isEqualTo("run:late")
        assertThat(resolved["to"]!!.jsonObject["from"]!!.jsonPrimitive.content).isEqualTo("global")
        assertThat(resolved["broker"]!!.jsonObject["from"]!!.jsonPrimitive.content).isEqualTo("flag")
    }

    @Test
    fun `an unknown run name suggests and lists runs`(
        @TempDir home: Path,
        @TempDir proj: Path,
    ) {
        val cfg = project(proj, "backtest:\n  runs:\n    late:\n      strategy: ${proj.resolve("strat.qkt")}\n")
        val err =
            catchThrowable {
                runBacktestWithRunsRoot(home, "backtest", "latee", "--config", cfg.toString())
            }
        assertThat(err).isInstanceOf(ArgError::class.java)
        assertThat(err).hasMessageContaining("no file 'latee' and no run 'latee'")
        assertThat(err).hasMessageContaining("Did you mean 'late'?")
    }

    @Test
    fun `bare backtest without a global strategy errors with guidance`(
        @TempDir home: Path,
        @TempDir proj: Path,
    ) {
        val cfg = project(proj, "backtest:\n  from: 2024-01-15\n")
        val err =
            catchThrowable {
                runBacktestWithRunsRoot(home, "backtest", "--config", cfg.toString())
            }
        assertThat(err).isInstanceOf(ArgError::class.java)
        assertThat(err).hasMessageContaining("no strategy given")
    }

    @Test
    fun `unknown config keys warn without failing`(
        @TempDir home: Path,
        @TempDir proj: Path,
    ) {
        val cfg =
            project(
                proj,
                """
                backtest:
                  strategy: ${proj.resolve("strat.qkt")}
                  from: 2024-01-15
                  to: 2024-01-16
                  bogus-key: 1
                """.trimIndent(),
            )
        val (code, _, stderr) =
            runBacktestWithRunsRoot(
                home,
                "backtest",
                "--config",
                cfg.toString(),
                "--data-root",
                "src/test/resources/cli/data",
                "--allow-incomplete",
                "--no-report",
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stderr).contains("backtest.bogus-key")
    }
}
