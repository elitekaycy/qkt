package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestRunsReportTest : BacktestCommandFixture() {
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


    @Test
    fun `report-dir from config is honored and named`(
        @TempDir home: Path,
        @TempDir proj: Path,
    ) {
        val out = proj.resolve("out")
        val cfg =
            project(
                proj,
                """
                backtest:
                  strategy: ${proj.resolve("strat.qkt")}
                  from: 2024-01-15
                  to: 2024-01-16
                  report-dir: ${out}
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
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stderr).contains("Report saved: $out")
        assertThat(out.resolve("report.html")).exists()
    }

    @Test
    fun `report dir base from config collects timestamped runs`(
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
                report:
                  dir: bundles
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
            )
        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        val saved = Files.list(proj.resolve("bundles")).use { it.toList() }
        assertThat(saved).hasSize(1)
        assertThat(saved[0].resolve("report.html")).exists()
    }
}
