package com.qkt.backtest

import com.qkt.backtest.TickResolvedParityFixtures.field
import com.qkt.backtest.TickResolvedParityFixtures.instrumentsFile
import com.qkt.backtest.TickResolvedParityFixtures.normalizedReport
import com.qkt.backtest.TickResolvedParityFixtures.runJson
import com.qkt.backtest.TickResolvedParityFixtures.seedTicks
import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `qkt backtest --bars` over bars the store has no ticks for (#1439): those bars fill exactly as
 * `--no-tick-fills` fills them, under paper and mt5-sim alike, and the run records how many there were.
 */
class NoTickBarsBacktestTest {
    private val from = "2024-01-02"
    private val to = "2024-01-07"

    /** Ticks and 15m bars for five days, then the ticks of [dropDays] deleted (the bars stay). */
    private fun store(
        dir: Path,
        dropDays: List<String>,
    ): Path {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 5)
        val build =
            DataCommand(
                Args(
                    arrayOf("data", "build-bars", "XAUUSD", "--tf", "15m", "--from", from, "--to", to)
                        .plus(arrayOf("--data-root", dataRoot.toString())),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)
        for (day in dropDays) Files.delete(dataRoot.resolve("symbols").resolve("XAUUSD").resolve("$day.bin"))
        return dataRoot
    }

    private fun withoutEvidence(json: String) = JsonObject(normalizedReport(json) - "evidence")

    private fun barsWithoutTicks(json: String) =
        normalizedReport(json)
            .getValue("evidence")
            .jsonObject
            .getValue("execution")
            .jsonObject["barsWithoutTicks"]
            ?.jsonArray
            ?.single()
            ?.jsonObject

    @Test
    fun `bars without any ticks fill exactly as --no-tick-fills fills them`(
        @TempDir dir: Path,
    ) {
        val dataRoot =
            store(dir, dropDays = listOf("2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05", "2024-01-06"))

        val tickFills = runJson(dir, dataRoot, from, to, extra = listOf("--bars"))
        val barFills = runJson(dir, dataRoot, from, to, extra = listOf("--bars", "--no-tick-fills"))

        assertThat(field(barFills, "trades").toInt()).isGreaterThan(0)
        assertThat(withoutEvidence(tickFills)).isEqualTo(withoutEvidence(barFills))
        val counts = barsWithoutTicks(tickFills)
        assertThat(counts?.getValue("symbol")?.jsonPrimitive?.content).isEqualTo("BACKTEST:XAUUSD")
        assertThat(counts?.getValue("fill")?.jsonPrimitive?.content).isEqualTo("level")
        assertThat(barsWithoutTicks(barFills)).isNull()
    }

    @Test
    fun `a window partly covered by ticks counts only the bars of the days without them`(
        @TempDir dir: Path,
    ) {
        val dataRoot = store(dir, dropDays = listOf("2024-01-04"))

        val counts = barsWithoutTicks(runJson(dir, dataRoot, from, to, extra = listOf("--bars")))

        // One UTC day of 15m bars had no ticks; every other bar filled from its real ticks.
        assertThat(counts?.getValue("allWithoutTicks")?.jsonPrimitive?.content).isEqualTo("96")
    }

    @Test
    fun `mt5-sim runs over bars without ticks and fills them at their levels instead of refusing`(
        @TempDir dir: Path,
    ) {
        val dataRoot = store(dir, dropDays = listOf("2024-01-03"))
        val mt5 = listOf("--bars", "--broker", "mt5-sim", "--instruments", instrumentsFile(dir).toString())

        val json = runJson(dir, dataRoot, from, to, extra = mt5)

        assertThat(field(json, "trades").toInt()).isGreaterThan(0)
        assertThat(barsWithoutTicks(json)?.getValue("allWithoutTicks")?.jsonPrimitive?.content).isEqualTo("96")
    }
}
