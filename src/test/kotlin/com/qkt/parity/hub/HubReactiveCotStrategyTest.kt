package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * cot_crowd_gold exactly as run on the production store (docs/research, 2026-10-27): hourly gold, a
 * weekly positioning record, a two-sided 52-record z-score of a field ratio, a 20-day hold. The
 * year of records before the run is its warmup, so the first records of the run are judged.
 */
class HubReactiveCotStrategyTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")
    private val hour = 3_600_000L
    private val week = 7 * 24 * hour

    private val strategy =
        """
        STRATEGY cot_crowd_gold VERSION 1
        SYMBOLS
            g   = BACKTEST:XAUUSD EVERY 1h,
            cot = HUB:pos.cftc.cot_gold.XAUUSD EVERY 7d
        RULES
            WHEN NOW.epoch_ms - cot.known_at <= 14400000 AND zscore(cot.net_noncomm / cot.open_interest, 52) >= 2 AND POSITION.g = 0
            THEN SELL g SIZING 1 EXIT AFTER 20d
            WHEN NOW.epoch_ms - cot.known_at <= 14400000 AND zscore(cot.net_noncomm / cot.open_interest, 52) <= -2 AND POSITION.g = 0
            THEN BUY g SIZING 1 EXIT AFTER 20d
        """.trimIndent()

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    @Test
    fun `crowded longs are sold and crowded shorts bought, each on its own weekly record, from the first week`(
        @TempDir tmp: Path,
    ) {
        // A record every Tuesday at 19:30 from 55 weeks before the run. Net positioning alternates 10 and
        // 11 of an open interest of 100 (z about ±1), except week 0 (30: z far above 2) and week 4
        // (-10: far below -2). The 19:00 bar closes at 20:00 with the record 30 minutes old.
        val net = mapOf(0 to "30", 4 to "-10")
        val records =
            (-55..7).map { k ->
                val known = t0.toEpochMilli() + k * week + 19 * hour + 30 * 60_000L
                val value = net[k] ?: if (k % 2 == 0) "10" else "11"
                Rec("XAUUSD", known, mapOf("net_noncomm" to value, "open_interest" to "100"))
            }
        val root =
            HubFixtureStore(tmp)
                .dataset("pos.cftc.cot_gold", listOf(Field("net_noncomm"), Field("open_interest")), records)
                .write()
        val end = at(t0.toEpochMilli() + 8 * week)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), t0, end, stepMs = hour)
        val runs = HubScenarioHarness.run(root, strategy, prices, t0, end, window = "1h")

        val sellAt = t0.toEpochMilli() + 20 * hour
        val buyAt = sellAt + 4 * week

        fun fill(
            ms: Long,
            side: String,
        ) = HubScenarioHarness.Fill(at(ms), "BACKTEST:XAUUSD", side)
        val expected =
            listOf(
                fill(sellAt, "SELL"),
                fill(sellAt + 20 * 24 * hour, "BUY"),
                fill(buyAt, "BUY"),
                fill(buyAt + 20 * 24 * hour, "SELL"),
            )
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }
}
