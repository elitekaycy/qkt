package com.qkt.parity.hub

import com.qkt.parity.hub.MeasuredStrategyScenario.Companion.known
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The real-yield and 2-year strategies measured on XAUUSD and EURUSD (docs/research, 2026-10-27), each verbatim. */
class HubMeasuredRatesStrategiesTest {
    private fun fred(dataset: String) = MeasuredStrategyScenario(dataset, "USD", "change_1d", "BACKTEST:XAUUSD", 13)

    private fun daily(changes: Map<Int, String?>): List<Pair<Long, String?>> =
        (0..12).map { d -> known(d, 20) to (if (d in changes) changes[d] else "0.01") }

    @Test
    fun `ry_down_gold buys a real-yield fall for five days, and a four-hour window takes the record its exit hid`(
        @TempDir tmp: Path,
    ) {
        // FRED-like prints at 20:30. -0.07 does not buy; -0.08 buys at 21:00; -0.2 while long is ignored.
        // Day 6's -0.1 lands in the hour the hold ends: at 21:00 still long, at 22:00 flat and the record
        // 90 minutes old, inside four hours, so it buys then.
        val strategy =
            """
            STRATEGY ry_down_gold VERSION 1
            SYMBOLS
                g  = BACKTEST:XAUUSD EVERY 1h,
                ry = HUB:rates.us.dfii10.USD EVERY 1d
            RULES
                WHEN NOW.epoch_ms - ry.known_at <= 14400000 AND ry.change_1d <= -0.08 AND POSITION.g = 0
                THEN BUY g SIZING 1 EXIT AFTER 5d
            """
        fred("rates.us.dfii10").assertFills(
            tmp,
            strategy,
            daily(mapOf(0 to "-0.07", 1 to "-0.08", 2 to "-0.2", 6 to "-0.1")),
            listOf(Triple(1, 21, "BUY"), Triple(6, 21, "SELL"), Triple(6, 22, "BUY"), Triple(11, 22, "SELL")),
        )
    }

    @Test
    fun `ry_up_gold sells a real-yield rise at its bound, and an empty holiday print changes nothing`(
        @TempDir tmp: Path,
    ) {
        val strategy =
            """
            STRATEGY ry_up_gold VERSION 1
            SYMBOLS
                g  = BACKTEST:XAUUSD EVERY 1h,
                ry = HUB:rates.us.dfii10.USD EVERY 1d
            RULES
                -- 10-year real yield up 8 bp or more on the day: gold down for a week.
                WHEN NOW.epoch_ms - ry.known_at <= 14400000 AND ry.change_1d >= 0.08 AND POSITION.g = 0
                THEN SELL g SIZING 1 EXIT AFTER 5d
            """
        fred("rates.us.dfii10").assertFills(
            tmp,
            strategy,
            daily(mapOf(0 to "0.0799", 1 to "0.08", 7 to null, 8 to "0.12")),
            listOf(Triple(1, 21, "SELL"), Triple(6, 21, "BUY"), Triple(8, 21, "SELL")),
        )
    }

    @Test
    fun `us2y_eur trades each side of a 2-year move for three days, not inside the band`(
        @TempDir tmp: Path,
    ) {
        // +0.10 sells; day 3's -0.10 lands in the hour that hold ends and buys an hour later; -0.0999
        // is inside the band; +0.15 sells.
        val strategy =
            """
            STRATEGY us2y_eur VERSION 1
            SYMBOLS
                e  = BACKTEST:EURUSD EVERY 1h,
                y2 = HUB:rates.us.dgs2.USD EVERY 1d
            RULES
                -- A 10 bp move in the 2-year yield: the dollar follows it for three days.
                WHEN NOW.epoch_ms - y2.known_at <= 14400000 AND y2.change_1d >= 0.10 AND POSITION.e = 0
                THEN SELL e SIZING 1 EXIT AFTER 3d
                WHEN NOW.epoch_ms - y2.known_at <= 14400000 AND y2.change_1d <= -0.10 AND POSITION.e = 0
                THEN BUY e SIZING 1 EXIT AFTER 3d
            """
        MeasuredStrategyScenario("rates.us.dgs2", "USD", "change_1d", "BACKTEST:EURUSD", 13).assertFills(
            tmp,
            strategy,
            daily(mapOf(0 to "0.10", 3 to "-0.10", 8 to "-0.0999", 9 to "0.15")),
            listOf(
                Triple(0, 21, "SELL"),
                Triple(3, 21, "BUY"),
                Triple(3, 22, "BUY"),
                Triple(6, 22, "SELL"),
                Triple(9, 21, "SELL"),
                Triple(12, 21, "BUY"),
            ),
        )
    }
}
