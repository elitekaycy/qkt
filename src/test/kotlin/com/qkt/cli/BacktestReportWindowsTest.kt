package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--metrics-window` / `--oos-split` windows and the daily equity artifacts of a real run (#1276, #1277). */
class BacktestReportWindowsTest : BarsReplayFixture() {
    private fun run(
        dir: Path,
        vararg extra: String,
    ): Int {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        val orig = System.out
        return try {
            System.setOut(PrintStream(ByteArrayOutputStream()))
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategyFile(dir).toString(),
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                        "--no-fetch",
                        "--allow-incomplete",
                        "--json",
                        "--report-dir",
                        dir.resolve("report").toString(),
                    ) + extra,
                ),
            ).run()
        } finally {
            System.setOut(orig)
        }
    }

    @Test
    fun `a window covering the run reproduces global and the split windows tile the run`(
        @TempDir dir: Path,
    ) {
        val code = run(dir, "--metrics-window", "all=2024-01-02..2024-01-05", "--oos-split", "0.5")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        val result = Json.parseToJsonElement(Files.readString(dir.resolve("report/result.json"))).jsonObject
        val global = result.getValue("global").jsonObject
        val windows = result.getValue("windows").jsonObject
        assertThat(windows.keys).containsExactly("all", "in_sample", "out_of_sample")
        val all = windows.getValue("all").jsonObject
        val allMetrics = all.getValue("metrics").jsonObject
        for (field in listOf(
            "maxDrawdown",
            "sharpeRatio",
            "sortinoRatio",
            "profitFactor",
            "winRate",
            "maxDailyDrawdown",
        )) {
            assertThat(allMetrics.getValue(field).jsonPrimitive.content)
                .describedAs(field)
                .isEqualTo(global.getValue(field).jsonPrimitive.content)
        }
        assertThat(global.getValue("annualizationFactor").jsonPrimitive.content).isNotEqualTo("null")
        assertThat(allMetrics.getValue("annualizationFactor").jsonPrimitive.content)
            .isEqualTo(global.getValue("annualizationFactor").jsonPrimitive.content)

        val inSample = windows.getValue("in_sample").jsonObject
        val oos = windows.getValue("out_of_sample").jsonObject
        assertThat(
            inSample.getValue("fromMs").jsonPrimitive.content,
        ).isEqualTo(all.getValue("fromMs").jsonPrimitive.content)
        assertThat(
            inSample.getValue("toMs").jsonPrimitive.content,
        ).isEqualTo(oos.getValue("fromMs").jsonPrimitive.content)
        assertThat(oos.getValue("toMs").jsonPrimitive.content).isEqualTo(all.getValue("toMs").jsonPrimitive.content)
        val closing = { w: kotlinx.serialization.json.JsonObject ->
            w
                .getValue("closingFills")
                .jsonPrimitive.content
                .toInt()
        }
        val samples = { w: kotlinx.serialization.json.JsonObject ->
            w
                .getValue("samples")
                .jsonPrimitive.content
                .toInt()
        }
        assertThat(closing(all)).isGreaterThan(0).isEqualTo(closing(inSample) + closing(oos))
        assertThat(samples(all)).isEqualTo(samples(inSample) + samples(oos))

        val daily = Files.readAllLines(dir.resolve("report/equity_daily.csv"))
        assertThat(daily.first()).isEqualTo("date,open,high,low,close")
        // Three trading days plus the bar that closes exactly at --to, stamped on the fourth.
        assertThat(daily.drop(1)).hasSize(4)
        val lastClose = daily.last().split(",")[4]
        assertThat(lastClose).isEqualTo(all.getValue("equityEnd").jsonPrimitive.content)
        val firstOpen = java.math.BigDecimal(daily[1].split(",")[1])
        val monthly = Files.readAllLines(dir.resolve("report/monthly_returns.csv"))
        assertThat(monthly).hasSize(2)
        assertThat(monthly[1]).startsWith("2024-01,")
        val expected =
            java.math
                .BigDecimal(
                    lastClose,
                ).subtract(firstOpen)
                .divide(firstOpen, com.qkt.common.Money.CONTEXT)
        assertThat(java.math.BigDecimal(monthly[1].substringAfter(",")))
            .isCloseTo(
                expected,
                org.assertj.core.data.Offset
                    .offset(java.math.BigDecimal("0.00000001")),
            )

        val manifest = Files.readString(dir.resolve("report/manifest.json"))
        assertThat(manifest).contains("\"equity_daily.csv\"").contains("\"monthly_returns.csv\"")
    }

    @Test
    fun `a malformed window spec is a user error`(
        @TempDir dir: Path,
    ) {
        assertThat(run(dir, "--metrics-window", "oos-2024-01-03")).isEqualTo(ExitCodes.USER_ERROR)
    }
}
