package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenReplayWindowStartTest {
    @Test
    fun `the replay window opens on the bar live first evaluated, not on the earliest captured tick`(
        @TempDir tmp: Path,
    ) {
        // A gateway's first prints after subscribe reach back long before the daemon decided anything:
        // live ticks from minute 0, while the strategy's first evaluation closes the bar [3m, 4m). A replay
        // from minute 0 would evaluate the 1m..3m closes live never evaluated and enter a bar early.
        val audit =
            listOf(
                journalTick(1, 1_000, "PAPER:BTC_USDC_PERPETUAL"),
                journalTick(2, 61_000, "PAPER:BTC_USDC_PERPETUAL"),
                journalTick(3, 185_000, "EXNESS:BTCUSD"),
                journalTick(4, 200_000, "PAPER:BTC_USDC_PERPETUAL"),
                journalEvaluation(5, "PAPER:BTC_USDC_PERPETUAL", "PAPER", 180_000, 240_000),
                journalEvaluation(6, "EXNESS:BTCUSD", "EXNESS", 180_000, 240_000),
                journalTick(7, 241_000, "EXNESS:BTCUSD"),
            )

        val window = replayWindow(tmp, audit)

        assertThat(window.first).isEqualTo(180_000L)
    }

    @Test
    fun `a session that never evaluated a bar keeps the window on the bar holding its first live tick`(
        @TempDir tmp: Path,
    ) {
        val audit = listOf(journalTick(1, 61_500, "EXNESS:XAUUSD"), journalCandle(2, "EXNESS:XAUUSD", 60_000, 120_000))

        val window = replayWindow(tmp, audit, transportMs = 61_500)

        assertThat(window.first).isEqualTo(60_000L)
    }

    @Test
    fun `a single-venue session whose first evaluation follows its first tick starts on the evaluated bar`(
        @TempDir tmp: Path,
    ) {
        // The daemon's first tick lands mid-bar at 1:30 and that bar is evaluated at 2:00: the replay starts
        // at 1:00, the same bar the old first-tick rule chose, so a single-venue replay is unchanged.
        val audit =
            listOf(
                journalTick(1, 90_000, "EXNESS:XAUUSD"),
                journalCandle(2, "EXNESS:XAUUSD", 60_000, 120_000),
                journalEvaluation(3, "EXNESS:XAUUSD", "EXNESS", 60_000, 120_000),
                journalTick(4, 121_000, "EXNESS:XAUUSD"),
            )

        val window = replayWindow(tmp, audit, transportMs = 90_000)

        assertThat(window.first).isEqualTo(60_000L)
        assertThat(window.second).isEqualTo(121_001L)
    }

    private fun replayWindow(
        tmp: Path,
        audit: List<String>,
        transportMs: Long = 1_100,
    ): Pair<Long, Long> {
        val root = captureAndMaterialize(tmp, audit, transportMs)
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("golden-replay-manifest.json"))).jsonObject
        val window = manifest.getValue("replayWindow").jsonObject
        return window.getValue("fromMs").jsonPrimitive.long to window.getValue("toMs").jsonPrimitive.long
    }
}
