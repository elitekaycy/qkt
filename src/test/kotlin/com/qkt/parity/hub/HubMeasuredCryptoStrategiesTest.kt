package com.qkt.parity.hub

import com.qkt.parity.hub.MeasuredStrategyScenario.Companion.known
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The Fear & Greed and funding strategies measured on BTCUSD (docs/research, 2026-10-27), each verbatim. */
class HubMeasuredCryptoStrategiesTest {
    private val fng = MeasuredStrategyScenario("text.crypto.fear_greed", "BTCUSD", "value", "BACKTEST:BTCUSD", 38)

    @Test
    fun `fng_fear_btc buys extreme fear at its bound, holds through more fear, and closes on neutral or at 30 days`(
        @TempDir tmp: Path,
    ) {
        // Daily at 00:30. 20 buys (the bound); 15 while long is ignored; 49 is not neutral; 50 closes.
        // 21 does not buy; 10 buys again, and with nothing neutral after it the 30-day cap closes it.
        val values = mapOf(1 to "20", 2 to "15", 3 to "49", 4 to "50", 5 to "21", 6 to "10")
        val strategy =
            """
            STRATEGY fng_fear_btc VERSION 1
            SYMBOLS
                x   = BACKTEST:BTCUSD EVERY 1h,
                fng = HUB:text.crypto.fear_greed.BTCUSD EVERY 1d
            RULES
                -- Extreme fear: buy; close when the index is back to neutral, at most 30 days.
                WHEN NOW.epoch_ms - fng.known_at <= 3600000 AND fng.value <= 20 AND POSITION.x = 0
                THEN BUY x SIZING 1 EXIT AFTER 30d
                WHEN NOW.epoch_ms - fng.known_at <= 3600000 AND fng.value >= 50 AND POSITION.x > 0
                THEN CLOSE x
            """
        val records = (0..37).map { d -> known(d, 0) to (values[d] ?: if (d < 6) "35" else "30") }
        fng.assertFills(
            tmp,
            strategy,
            records,
            listOf(Triple(1, 1, "BUY"), Triple(4, 1, "SELL"), Triple(6, 1, "BUY"), Triple(36, 1, "SELL")),
        )
    }

    @Test
    fun `fng_greed_btc sells extreme greed for a week and loses a record that lands in the hour its hold ends`(
        @TempDir tmp: Path,
    ) {
        // 79 does not sell; 80 sells day 1 and the hold ends day 8 01:00. Day 8's 85 is seen at that
        // close with the position still open, and is an hour and a half old at the next: never traded.
        val values = mapOf(0 to "79", 1 to "80", 3 to "90", 8 to "85", 10 to "95")
        val strategy =
            """
            STRATEGY fng_greed_btc VERSION 1
            SYMBOLS
                x   = BACKTEST:BTCUSD EVERY 1h,
                fng = HUB:text.crypto.fear_greed.BTCUSD EVERY 1d
            RULES
                WHEN NOW.epoch_ms - fng.known_at <= 3600000 AND fng.value >= 80 AND POSITION.x = 0
                THEN SELL x SIZING 1 EXIT AFTER 7d
            """
        val records = (0..18).map { d -> known(d, 0) to (values[d] ?: "50") }
        MeasuredStrategyScenario("text.crypto.fear_greed", "BTCUSD", "value", "BACKTEST:BTCUSD", 19).assertFills(
            tmp,
            strategy,
            records,
            listOf(Triple(1, 1, "SELL"), Triple(8, 1, "BUY"), Triple(10, 1, "SELL"), Triple(17, 1, "BUY")),
        )
    }

    private val funding = MeasuredStrategyScenario("rates.crypto.btc_funding", "BTCUSD", "rate", "BACKTEST:BTCUSD", 8)

    private fun fundingRecords(rates: Map<Int, String>): List<Pair<Long, String?>> =
        (0 until 24).map { k -> known(k / 3, (k % 3) * 8) to (rates[k] ?: "0.0001") }

    @Test
    fun `funding_hot_btc fades funding at its bound for three days, on the 8-hour prints`(
        @TempDir tmp: Path,
    ) {
        // Prints at 00:30, 08:30, 16:30. 0.0004999 does not sell; 0.0005 (day 0 08:30) does; 0.0009 while
        // short is ignored; day 3 08:30 lands in the hour the hold ends and is lost; day 3 16:30 sells.
        val strategy =
            """
            STRATEGY funding_hot_btc VERSION 1
            SYMBOLS
                x  = BACKTEST:BTCUSD EVERY 1h,
                fu = HUB:rates.crypto.btc_funding.BTCUSD EVERY 8h
            RULES
                -- Longs paying 0.05% per 8 h or more: crowded, fade for 3 days.
                WHEN NOW.epoch_ms - fu.known_at <= 3600000 AND fu.rate >= 0.0005 AND POSITION.x = 0
                THEN SELL x SIZING 1 EXIT AFTER 3d
            """
        val rates = mapOf(0 to "0.0004999", 1 to "0.0005", 2 to "0.0009", 10 to "0.0006", 11 to "0.0007")
        funding.assertFills(
            tmp,
            strategy,
            fundingRecords(rates),
            listOf(Triple(0, 9, "SELL"), Triple(3, 9, "BUY"), Triple(3, 17, "SELL"), Triple(6, 17, "BUY")),
        )
    }

    @Test
    fun `funding_neg_btc buys strictly negative funding for three days`(
        @TempDir tmp: Path,
    ) {
        // Zero is not negative; -0.00001 buys; -0.0003 while long is ignored; -0.0001 after the exit buys.
        val strategy =
            """
            STRATEGY funding_neg_btc VERSION 1
            SYMBOLS
                x  = BACKTEST:BTCUSD EVERY 1h,
                fu = HUB:rates.crypto.btc_funding.BTCUSD EVERY 8h
            RULES
                WHEN NOW.epoch_ms - fu.known_at <= 3600000 AND fu.rate < 0 AND POSITION.x = 0
                THEN BUY x SIZING 1 EXIT AFTER 3d
            """
        val rates = mapOf(0 to "0", 1 to "-0.00001", 4 to "-0.0003", 11 to "-0.0001")
        funding.assertFills(
            tmp,
            strategy,
            fundingRecords(rates),
            listOf(Triple(0, 9, "BUY"), Triple(3, 9, "SELL"), Triple(3, 17, "BUY"), Triple(6, 17, "SELL")),
        )
    }
}
