package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepBacktestParityTest : SweepCommandFixture() {
    private fun sweepSharpe(dir: Path): String {
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
        val json = out.toString().lines().lastOrNull { it.trimStart().startsWith("[") } ?: ""
        val row =
            Json
                .parseToJsonElement(json)
                .jsonArray
                .single()
                .jsonObject
        assertThat(row["label"]!!.jsonPrimitive.content).contains("fast=3")
        return row["sharpe"]!!.toString()
    }

    private fun backtestSharpe(dir: Path): String {
        val args =
            Args(
                arrayOf(
                    "backtest",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=3",
                    "--json",
                ),
            )
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out))
            assertThat(BacktestCommand(args, fetcherOverride = FakeXauFetcher).run()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(original)
        }
        val json =
            out
                .toString()
                .trim()
                .lines()
                .last()
        return Json
            .parseToJsonElement(json)
            .jsonObject["global"]!!
            .jsonObject["sharpeRatio"]
            .toString()
    }

    @Test
    fun `sweep sharpe matches backtest sharpe for the same params`(
        @TempDir dir: Path,
    ) {
        assertThat(sweepSharpe(dir)).isEqualTo(backtestSharpe(dir))
    }
}
