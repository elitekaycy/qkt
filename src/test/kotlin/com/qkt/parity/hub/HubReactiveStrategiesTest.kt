package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Two whole reactive strategies of the shapes run on the production store (docs/research,
 * 2026-10-26): a regime trade whose exit is a hub record, and a two-sided rule on one field. Records
 * land every 10 minutes, 30 s into their slot; a rule may act only within a minute of a record, so
 * each record is acted on at exactly one bar close (slot minute + 1) or not at all.
 */
class HubReactiveStrategiesTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")

    private fun fill(
        minute: Long,
        side: String,
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), "BACKTEST:XAUUSD", side)

    private fun runs(
        tmp: Path,
        field: String,
        values: Map<Int, String>,
        rest: String,
        rules: String,
    ): HubScenarioHarness.Runs {
        val records =
            (-6..12).map { k ->
                Rec("ALL", t0.toEpochMilli() + k * 600_000L + 30_000L, mapOf(field to (values[k] ?: rest)))
            }
        val root = HubFixtureStore(tmp).dataset("test.signal", listOf(Field(field)), records).write()
        val strategy =
            """
            STRATEGY reactive VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                s    = HUB:test.signal.ALL EVERY 10m
            RULES
            """.trimIndent() + "\n" + rules.trimIndent().prependIndent("    ")
        val end = t0.plusSeconds(2 * 3600 + 10 * 60)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), t0, end)
        return HubScenarioHarness.run(root, strategy, prices, t0, end)
    }

    private fun assertAllPaths(
        runs: HubScenarioHarness.Runs,
        expected: List<HubScenarioHarness.Fill>,
    ) {
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }

    @Test
    fun `a regime trade enters on an extreme record, ignores a second while long, and a later record closes it`(
        @TempDir tmp: Path,
    ) {
        // Fear at slot 1 buys; slot 2's fear finds the position open; slot 4's recovery closes it;
        // slot 6's fear buys again and slot 8's recovery closes that one.
        val runs =
            runs(
                tmp,
                "value",
                mapOf(1 to "15", 2 to "18", 4 to "55", 6 to "10", 8 to "50"),
                rest = "35",
                rules =
                    """
                    WHEN NOW.epoch_ms - s.known_at <= 60000 AND s.value <= 20 AND POSITION.gold = 0
                    THEN BUY gold SIZING 1 EXIT AFTER 1d
                    WHEN NOW.epoch_ms - s.known_at <= 60000 AND s.value >= 50 AND POSITION.gold > 0
                    THEN CLOSE gold
                    """,
            )
        assertAllPaths(runs, listOf(fill(11, "BUY"), fill(41, "SELL"), fill(61, "BUY"), fill(81, "SELL")))
    }

    @Test
    fun `a two-sided rule trades each side on its own record, at the threshold and not inside it`(
        @TempDir tmp: Path,
    ) {
        // +0.12 sells, -0.15 buys, +0.05 is inside the band, +0.10 is exactly the threshold and sells,
        // -0.0999 is just inside and does nothing.
        val runs =
            runs(
                tmp,
                "chg",
                mapOf(2 to "0.12", 5 to "-0.15", 7 to "0.05", 9 to "0.10", 11 to "-0.0999"),
                rest = "0",
                rules =
                    """
                    WHEN NOW.epoch_ms - s.known_at <= 60000 AND s.chg >= 0.10 AND POSITION.gold = 0
                    THEN SELL gold SIZING 1 EXIT AFTER 5m
                    WHEN NOW.epoch_ms - s.known_at <= 60000 AND s.chg <= -0.10 AND POSITION.gold = 0
                    THEN BUY gold SIZING 1 EXIT AFTER 5m
                    """,
            )
        assertAllPaths(
            runs,
            listOf(
                fill(21, "SELL"),
                fill(26, "BUY"),
                fill(51, "BUY"),
                fill(56, "SELL"),
                fill(91, "SELL"),
                fill(96, "BUY"),
            ),
        )
    }
}
