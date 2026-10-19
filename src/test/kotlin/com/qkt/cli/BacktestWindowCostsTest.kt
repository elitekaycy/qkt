package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--oos-split` windows carry their own costs, adding up to global, and print in the summary (#1451). */
class BacktestWindowCostsTest : BarsReplayFixture() {
    private fun instruments(dir: Path): Path {
        val f = dir.resolve("instruments.yaml")
        Files.writeString(
            f,
            """
            instruments:
              - qktSymbol: BACKTEST:XAUUSD
                contractSize: 100
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.001
                digits: 3
                tradeStopsLevelPoints: 0
                swapLongPoints: -516.5
                swapShortPoints: 0
                swapRolloverHourUtc: 21
                swapTripleDay: WEDNESDAY
            """.trimIndent(),
        )
        return f
    }

    @Test
    fun `split windows report their own commission and swap and the summary shows them`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        val buf = ByteArrayOutputStream()
        val orig = System.out
        val code =
            try {
                System.setOut(PrintStream(buf))
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
                            "--instruments",
                            instruments(dir).toString(),
                            "--commission-per-lot",
                            "3.5",
                            "--no-fetch",
                            "--allow-incomplete",
                            "--oos-split",
                            "2024-01-04",
                            "--report-dir",
                            dir.resolve("report").toString(),
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(orig)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        val result = Json.parseToJsonElement(Files.readString(dir.resolve("report/result.json"))).jsonObject
        val global = result.getValue("global").jsonObject
        val windows =
            result
                .getValue("windows")
                .jsonObject.values
                .map { it.jsonObject.getValue("metrics").jsonObject }

        fun JsonObject.amount(field: String) = BigDecimal(getValue(field).jsonPrimitive.content)
        for (field in listOf("commissionPaid", "swapPaid")) {
            assertThat(global.amount(field)).describedAs(field).isNotZero()
            assertThat(windows.map { it.amount(field) }.reduce(BigDecimal::add))
                .describedAs(field)
                .isEqualByComparingTo(global.amount(field))
        }
        assertThat(windows).allSatisfy { assertThat(it.amount("commissionPaid")).isNotZero() }
        val roundTrips =
            windows.sumOf {
                it
                    .getValue("roundTrips")
                    .jsonPrimitive.content
                    .toInt()
            }
        assertThat(roundTrips).isEqualTo(
            global
                .getValue("roundTrips")
                .jsonPrimitive.content
                .toInt(),
        )

        val text = buf.toString()
        assertThat(text).contains("In-sample / out-of-sample", "In sample", "Out of sample", "commission")
        assertThat(Files.readString(dir.resolve("report/report.html"))).contains("In-sample and out-of-sample")
    }
}
