package com.qkt.cli

import com.qkt.cli.golden.captureAndMaterialize
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Orders several venues decide on one bar close take their ids in the order their closing ticks reached
 * live qkt (#1625): a golden replay processes captured ticks in arrival order, not in venue-stamp order.
 */
class GoldenArrivalOrderReplayTest : BacktestCommandFixture() {
    private val day = 1_705_276_800_000L

    private fun tick(
        seq: Long,
        receivedMs: Long,
        stampMs: Long,
        symbol: String,
        price: String,
    ): String =
        """{"v":1,"ts":$receivedMs,"seq":$seq,"eventType":"com.qkt.events.TickEvent","symbol":"$symbol",""" +
            """"tick":{"timestampMs":$stampMs,"price":"$price","bid":"$price","ask":"$price"}}"""

    @Test
    fun `two venues closing one bar take the order ids their arrival gave them live`(
        @TempDir tmp: Path,
    ) {
        // EXNESS stamps its closing tick first (+10 ms) but it reaches qkt last (+900 ms, an MT5 poll);
        // PAPER's closing tick, stamped +50 ms, arrives at +100 ms. Live numbered PAPER's order first.
        val audit =
            listOf(
                tick(1, day + 1_000, day + 1_000, "EXNESS:BTCUSD", "270"),
                tick(2, day + 2_000, day + 2_000, "PAPER:BTCUSD", "150"),
                tick(3, day + 60_100, day + 60_050, "PAPER:BTCUSD", "210"),
                tick(4, day + 60_900, day + 60_010, "EXNESS:BTCUSD", "190"),
            )
        val data = captureAndMaterialize(tmp, audit, transportMs = day + 30_000)
        val strategy = tmp.resolve("pair.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY pair VERSION 1

            SYMBOLS
                ex = EXNESS:BTCUSD EVERY 1m
                pa = PAPER:BTCUSD EVERY 1m

            RULES
                WHEN ex.close > 200
                THEN BUY ex SIZING 1
                WHEN pa.close < 200
                THEN BUY pa SIZING 1
            """.trimIndent() + "\n",
        )
        val report = tmp.resolve("report")

        val (code, _, stderr) =
            runBacktest(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                data.toString(),
                "--no-fetch",
                "--allow-incomplete",
                "--report-dir",
                report.toString(),
                "--json",
            )

        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        val orders = Files.readAllLines(report.resolve("orders.jsonl")).filter { it.isNotBlank() }
        assertThat(orders).hasSize(2)
        assertThat(orders[0]).contains("\"orderId\":\"ORD-pair-0\"").contains("\"symbol\":\"PAPER:BTCUSD\"")
        assertThat(orders[1]).contains("\"orderId\":\"ORD-pair-1\"").contains("\"symbol\":\"EXNESS:BTCUSD\"")
    }

    @Test
    fun `a venue tick that arrived later than a newer-stamped one replays after it, as live heard it`(
        @TempDir tmp: Path,
    ) {
        // PAPER's tick stamped +200 ms reaches qkt at +1500 ms, after the tick stamped +500 ms (arrived +600 ms).
        // Live closed the bar and filled on the +500 ms tick; a replay in stamp order would fill on the other.
        val audit =
            listOf(
                tick(1, day + 1_000, day + 1_000, "PAPER:BTCUSD", "100"),
                tick(2, day + 60_600, day + 60_500, "PAPER:BTCUSD", "300"),
                tick(3, day + 61_500, day + 60_200, "PAPER:BTCUSD", "250"),
            )
        val data = captureAndMaterialize(tmp, audit, transportMs = day + 30_000)
        val strategy = tmp.resolve("one.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY one VERSION 1

            SYMBOLS
                pa = PAPER:BTCUSD EVERY 1m

            RULES
                WHEN pa.close > 0
                THEN BUY pa SIZING 1
            """.trimIndent() + "\n",
        )
        val report = tmp.resolve("report")

        val (code, _, stderr) =
            runBacktest(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                data.toString(),
                "--no-fetch",
                "--allow-incomplete",
                "--report-dir",
                report.toString(),
                "--json",
            )

        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        val trades = Files.readAllLines(report.resolve("trades.csv")).drop(1).filter { it.isNotBlank() }
        assertThat(trades).withFailMessage("trades=$trades").isNotEmpty()
        assertThat(trades[0]).contains(",300")
    }
}
