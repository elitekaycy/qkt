package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Determinism acceptance matrix for every backtest execution tier exposed by the CLI. */
class CliTierDeterminismTest : CliTierDeterminismFixture() {
    private data class Tier(
        val name: String,
        val strategy: Path,
        val extraArgs: List<String> = emptyList(),
    )

    private data class RunOutput(
        val json: String,
        val reportFiles: Map<String, ByteArray>,
    )

    @Test
    fun `each CLI execution tier is byte-for-byte deterministic`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, "XAUUSD", base = 1850.0, amplitude = 30.0, period = 7.0)
        seedTicks(dataRoot, "XAGUSD", base = 24.0, amplitude = 0.4, period = 5.0)
        listOf("XAUUSD", "XAGUSD").forEach { symbol -> buildBars(dataRoot, symbol) }

        val strategy = strategyFile(dir, "single.qkt", "single", "XAUUSD", "gold")
        val portfolio = portfolioFile(dir)
        val instruments = instrumentsFile(dir)
        val tiers =
            listOf(
                Tier("dsl", strategy),
                Tier("mt5-sim", strategy, listOf("--broker", "mt5-sim", "--instruments", instruments.toString())),
                Tier("bars", strategy, listOf("--bars")),
                Tier("tick-fills", strategy, listOf("--bars", "--tick-fills")),
                Tier("portfolio", portfolio),
            )

        tiers.forEach { tier ->
            val reportDir = dir.resolve("reports/${tier.name}")
            val first = runTier(tier, dataRoot, reportDir)
            val second = runTier(tier, dataRoot, reportDir)

            assertThat(second.json).describedAs("${tier.name} JSON").isEqualTo(first.json)
            assertThat(second.reportFiles.keys)
                .describedAs("${tier.name} report files")
                .containsExactlyInAnyOrderElementsOf(first.reportFiles.keys)
            first.reportFiles.forEach { (name, expected) ->
                assertThat(second.reportFiles.getValue(name))
                    .describedAs("${tier.name}/$name")
                    .isEqualTo(expected)
            }
            assertThat(first.reportFiles).containsKey("trades.csv")
        }
    }

    private fun runTier(
        tier: Tier,
        dataRoot: Path,
        reportDir: Path,
    ): RunOutput {
        val stdout = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(stdout))
                BacktestCommand(
                    Args(
                        (
                            listOf(
                                "backtest",
                                tier.strategy.toString(),
                                "--from",
                                "2024-01-02",
                                "--to",
                                "2024-01-03",
                                "--data-root",
                                dataRoot.toString(),
                                "--no-fetch",
                                "--allow-incomplete",
                                "--json",
                                "--report-dir",
                                reportDir.toString(),
                            ) + tier.extraArgs
                        ).toTypedArray(),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }
        assertThat(code).describedAs(tier.name).isEqualTo(ExitCodes.SUCCESS)
        val json =
            stdout
                .toString()
                .lineSequence()
                .map(String::trim)
                .single { it.startsWith("{") }
        val trades =
            Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("trades")
                .jsonPrimitive
                .content
                .toInt()
        assertThat(trades)
            .describedAs("${tier.name} trades")
            .isGreaterThan(0)
        val files =
            Files.walk(reportDir).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .sorted()
                    .toList()
                    .associate { path -> path.relativeTo(reportDir).toString() to Files.readAllBytes(path) }
            }
        return RunOutput(json, files)
    }
}
