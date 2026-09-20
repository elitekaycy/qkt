package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepCommandJsonTest : SweepCommandFixture() {
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
    fun `sweep json carries daily pnl drawdown and fill cost inputs per combo`(
        @TempDir dir: Path,
    ) {
        val args =
            Args(
                arrayOf(
                    "sweep",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=3",
                    "--large-search-threshold",
                    "0",
                    "--json",
                ),
            )
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out))
            SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        } finally {
            System.setOut(original)
        }
        val json = out.toString().lines().lastOrNull { it.trimStart().startsWith("[") } ?: ""
        assertThat(json).startsWith("[")
        assertThat(json).contains("\"trialCount\":1")
        assertThat(json).contains("\"metricProvenance\":{\"selectedMetric\":\"sharpe\",\"source\":\"sweep\"")
        assertThat(json).contains("\"selectionWarnings\":")
        assertThat(json).contains("\"commissionPaid\":")
        assertThat(json).contains("\"dailyPnL\":")
        assertThat(json).contains("\"maxDailyDrawdown\":")
        val row =
            Json
                .parseToJsonElement(json)
                .jsonArray
                .single()
                .jsonObject
        val summaries = row.getValue("fillCostSummary").jsonArray
        assertThat(
            row
                .getValue("commissionPaid")
                .jsonPrimitive.content
                .toBigDecimal(),
        ).isGreaterThanOrEqualTo(BigDecimal.ZERO)
        assertThat(summaries).isNotEmpty
        assertThat(
            summaries.sumOf {
                it.jsonObject
                    .getValue("fills")
                    .jsonPrimitive
                    .content
                    .toInt()
            },
        ).isEqualTo(
            row
                .getValue("trades")
                .jsonPrimitive
                .content
                .toInt(),
        )
        assertThat(
            summaries.all {
                val summary = it.jsonObject
                summary
                    .getValue("day")
                    .jsonPrimitive
                    .content == "2026-06-04" &&
                    summary
                        .getValue("symbol")
                        .jsonPrimitive
                        .content == "BACKTEST:XAUUSD" &&
                    summary
                        .getValue("lotsAbs")
                        .jsonPrimitive
                        .content
                        .toBigDecimal()
                        .signum() > 0 &&
                    summary
                        .getValue("notionalAbs")
                        .jsonPrimitive
                        .content
                        .toBigDecimal()
                        .signum() > 0
            },
        ).isTrue()
    }

    @Test
    fun `sweep json preserves pinned dataset identity per combo`(
        @TempDir dir: Path,
    ) {
        val dataset = snapshot(dir)
        val args =
            Args(
                arrayOf(
                    "sweep",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--dataset",
                    dataset.toString(),
                    "--param",
                    "fast=2,3",
                    "--json",
                ),
            )
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out))
            assertThat(SweepCommand(args, fetcherOverride = FakeXauFetcher).run()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(original)
        }

        val json = out.toString().lines().lastOrNull { it.trimStart().startsWith("[") }
        assertThat(json).isNotNull()
        assertThat(json!!)
            .startsWith("[")
            .contains("\"dataset\":{")
            .contains("\"id\":\"qkt-ds-xauusd-2026-06-04_2026-06-05-")
            .contains("\"hash\":\"sha256:")
            .contains("\"mutableStore\":false")
    }
}
