package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.relativeTo
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Determinism acceptance matrix for every backtest execution tier exposed by the CLI. */
class CliTierDeterminismTest {
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

    private fun seedTicks(
        dataRoot: Path,
        symbol: String,
        base: Double,
        amplitude: Double,
        period: Double,
    ) {
        val day = LocalDate.parse("2024-01-02")
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ticks =
            (0 until 1440).map { minute ->
                val price = base + amplitude * sin(minute / period)
                Tick(symbol, Money.of("%.3f".format(price)), start + minute * 60_000L)
            }
        val target = dataRoot.resolve("symbols/$symbol/$day.bin")
        Files.createDirectories(target.parent)
        BinaryTickWriter().write(target, symbol, ticks)
    }

    private fun buildBars(
        dataRoot: Path,
        symbol: String,
    ) {
        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        symbol,
                        "--tf",
                        "15m",
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-03",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    private fun strategyFile(
        dir: Path,
        fileName: String,
        strategyName: String,
        symbol: String,
        alias: String,
    ): Path {
        val path = dir.resolve(fileName)
        Files.writeString(
            path,
            """
            STRATEGY $strategyName VERSION 1
            SYMBOLS
                $alias = BACKTEST:$symbol EVERY 15m
            RULES
                WHEN ema($alias.close, 3) CROSSES ABOVE ema($alias.close, 9)
                THEN BUY $alias SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema($alias.close, 3) CROSSES BELOW ema($alias.close, 9)
                THEN CLOSE $alias
            """.trimIndent(),
        )
        return path
    }

    private fun portfolioFile(dir: Path): Path {
        strategyFile(dir, "gold-child.qkt", "gold_child", "XAUUSD", "gold")
        strategyFile(dir, "silver-child.qkt", "silver_child", "XAGUSD", "silver")
        return dir.resolve("book.qkt").also { path ->
            Files.writeString(
                path,
                """
                PORTFOLIO deterministic_book VERSION 1
                IMPORT 'gold-child.qkt' AS gold
                IMPORT 'silver-child.qkt' AS silver
                RULES
                    RUN gold
                    RUN silver
                """.trimIndent(),
            )
        }
    }

    private fun instrumentsFile(dir: Path): Path =
        dir.resolve("instruments.yaml").also { path ->
            Files.writeString(
                path,
                """
                instruments:
                  - qktSymbol: BACKTEST:XAUUSD
                    contractSize: 100
                    volumeStep: 0.01
                    volumeMin: 0.01
                    pointSize: 0.001
                    digits: 3
                    tradeStopsLevelPoints: 0
                """.trimIndent(),
            )
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
