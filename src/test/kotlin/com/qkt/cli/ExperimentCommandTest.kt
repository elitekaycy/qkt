package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExperimentCommandTest {
    private fun strategy(dir: Path): Path {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            LET fast = 3
            RULES
                WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, fast) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return strat
    }

    private fun snapshot(root: Path): Path {
        val dataRoot = root.resolve("data")
        FakeXauFetcher.fetch(
            "XAUUSD",
            LocalDate.parse("2026-06-04"),
            dataRoot.resolve("symbols").resolve("XAUUSD").resolve("2026-06-04.csv.gz"),
        )
        val out = root.resolve("xau-snapshot.json")
        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "snapshot",
                        "XAUUSD",
                        "--from",
                        "2026-06-04",
                        "--to",
                        "2026-06-05",
                        "--data-root",
                        dataRoot.toString(),
                        "--out",
                        out.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        return out
    }

    @Test
    fun `experiment run writes immutable registry record and split-aware reports`(
        @TempDir dir: Path,
    ) {
        val strat = strategy(dir)
        val dataset = snapshot(dir)
        val plan = dir.resolve("plan.yaml")
        Files.writeString(
            plan,
            """
            name: xau-research
            strategy: ${strat.toAbsolutePath()}
            dataset: ${dataset.toAbsolutePath()}
            primary_metric: totalPnL
            secondary_metrics: [calmar]
            splits:
              train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z
              validation: 2026-06-04T04:00:00Z/2026-06-04T08:00:00Z
              test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z
            parameter_grid:
              fast: [2, 3]
            selection:
              top_n: 1
              large_search_threshold: 1
            promotion:
              state: candidate
              rationale: validation winner ready for paper review
            seed: 7
            """.trimIndent(),
        )
        val registry = dir.resolve("registry")
        val outDir = dir.resolve("experiment-out")
        val stdout = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(stdout, true))
                ExperimentCommand(
                    Args(
                        arrayOf(
                            "experiment",
                            "run",
                            "--plan",
                            plan.toString(),
                            "--data-root",
                            dir.resolve("data").toString(),
                            "--registry-dir",
                            registry.toString(),
                            "--out-dir",
                            outDir.toString(),
                            "--parallelism",
                            "2",
                        ),
                    ),
                    fetcherOverride = FakeXauFetcher,
                ).run()
            } finally {
                System.setOut(original)
            }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout.toString()).contains("experiment: xau-research")
        assertThat(registry.resolve("experiments.jsonl")).exists()
        assertThat(Files.list(registry.resolve("objects")).count()).isEqualTo(1L)
        assertThat(outDir.resolve("train_summary.json")).exists()
        assertThat(outDir.resolve("validation_summary.json")).exists()
        assertThat(outDir.resolve("test/result.json")).exists()
        assertThat(outDir.resolve("test/report.html")).exists()

        val record = Files.readString(registry.resolve("experiments.jsonl"))
        assertThat(record)
            .contains("\"name\":\"xau-research\"")
            .contains("\"strategy\":{")
            .contains("\"hash\":\"sha256:")
            .contains("\"dataset\":{")
            .contains("\"trialCount\":2")
            .contains("\"selectedBy\":\"validation.totalPnL\"")
            .contains("\"state\":\"candidate\"")
            .contains("validation winner ready for paper review")
            .contains("multiple-comparison exposed")

        val resultJson = Files.readString(outDir.resolve("test/result.json"))
        assertThat(resultJson)
            .contains("\"experiment\":{\"id\":\"xau-research\"")
            .contains("\"splits\":{\"test\":\"2026-06-04T08:00:00Z/2026-06-04T12:00:00Z\"")
            .contains("\"selectedParams\":{\"fast\":")
            .contains("\"rationale\":\"validation winner ready for paper review\"")

        val html = Files.readString(outDir.resolve("test/report.html"))
        assertThat(html)
            .contains("split.train")
            .contains("2026-06-04T00:00:00Z/2026-06-04T04:00:00Z")
            .contains("promotion rationale")
    }
}
