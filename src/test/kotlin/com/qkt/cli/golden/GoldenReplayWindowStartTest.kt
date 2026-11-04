package com.qkt.cli.golden

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.GoldenCommand
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
                tick(1, 1_000, "PAPER:BTC_USDC_PERPETUAL"),
                tick(2, 61_000, "PAPER:BTC_USDC_PERPETUAL"),
                tick(3, 185_000, "EXNESS:BTCUSD"),
                tick(4, 200_000, "PAPER:BTC_USDC_PERPETUAL"),
                evaluated(5, "PAPER:BTC_USDC_PERPETUAL", "PAPER", 180_000, 240_000),
                evaluated(6, "EXNESS:BTCUSD", "EXNESS", 180_000, 240_000),
                tick(7, 241_000, "EXNESS:BTCUSD"),
            )

        val window = replayWindow(tmp, audit)

        assertThat(window.first).isEqualTo(180_000L)
    }

    @Test
    fun `a session that never evaluated a bar keeps the window on the bar holding its first live tick`(
        @TempDir tmp: Path,
    ) {
        val audit = listOf(tick(1, 61_500, "EXNESS:XAUUSD"), candle(2, "EXNESS:XAUUSD", 60_000, 120_000))

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
                tick(1, 90_000, "EXNESS:XAUUSD"),
                candle(2, "EXNESS:XAUUSD", 60_000, 120_000),
                evaluated(3, "EXNESS:XAUUSD", "EXNESS", 60_000, 120_000),
                tick(4, 121_000, "EXNESS:XAUUSD"),
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
        val journal = tmp.resolve("state/audit-journal/alpha/audit-1970-01-01.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-1970-01-01.jsonl")
        Files.createDirectories(journal.parent)
        Files.createDirectories(transport.parent)
        Files.writeString(journal, audit.joinToString("\n", postfix = "\n"))
        Files.writeString(
            transport,
            """{"v":1,"ts":$transportMs,"method":"GET","path":"/account","responseCode":200}""" + "\n",
        )
        val bundle = tmp.resolve("golden.zip")
        val capture =
            arrayOf(
                "golden",
                "capture",
                "--session",
                "alpha",
                "--state-dir",
                tmp.toString(),
                "--out",
                bundle.toString(),
                "--read-only",
            )
        assertThat(GoldenCommand(Args(capture)).run()).isEqualTo(ExitCodes.SUCCESS)
        val root = tmp.resolve("replay")
        val materialize = arrayOf("golden", "materialize", "--bundle", bundle.toString(), "--out", root.toString())
        assertThat(GoldenCommand(Args(materialize)).run()).isEqualTo(ExitCodes.SUCCESS)
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("golden-replay-manifest.json"))).jsonObject
        val window = manifest.getValue("replayWindow").jsonObject
        return window.getValue("fromMs").jsonPrimitive.long to window.getValue("toMs").jsonPrimitive.long
    }

    private fun tick(
        seq: Long,
        ms: Long,
        symbol: String,
    ): String =
        """{"v":1,"ts":$ms,"seq":$seq,"eventType":"com.qkt.events.TickEvent","symbol":"$symbol",""" +
            """"tick":{"timestampMs":$ms,"price":"100","bid":"99.9","ask":"100.1"}}"""

    private fun candle(
        seq: Long,
        symbol: String,
        startMs: Long,
        endMs: Long,
    ): String =
        """{"v":1,"ts":$endMs,"seq":$seq,"eventType":"com.qkt.events.CandleEvent","symbol":"$symbol",""" +
            """"candle":${candleBlock(startMs, endMs)}}"""

    private fun evaluated(
        seq: Long,
        symbol: String,
        broker: String,
        startMs: Long,
        endMs: Long,
    ): String =
        """{"v":1,"ts":$endMs,"seq":$seq,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent",""" +
            """"strategyId":"alpha","symbol":"$symbol","alias":"m1","broker":"$broker","timeframe":"1m",""" +
            """"rulesEvaluated":1,"candle":${candleBlock(startMs, endMs)}}"""

    private fun candleBlock(
        startMs: Long,
        endMs: Long,
    ): String =
        """{"startTimeMs":$startMs,"endTimeMs":$endMs,"open":"100","high":"100","low":"100","close":"100",""" +
            """"volume":"1"}"""
}
