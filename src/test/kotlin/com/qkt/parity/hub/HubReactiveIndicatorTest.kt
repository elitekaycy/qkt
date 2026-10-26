package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * An indicator over an expression of two fields of one hub stream, the shape of a news-spike rule
 * (`zscore(news.conflict / news.events, n)`): it advances once per record, reads both fields of
 * that record, and the rule acts on the record that makes it spike.
 */
class HubReactiveIndicatorTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")

    private fun fill(
        minute: Long,
        side: String,
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), "BACKTEST:XAUUSD", side)

    private val fresh = "NOW.epoch_ms - news.known_at <= 60000"
    private val spike = "zscore(news.conflict / news.events, 5) >= 1.5"

    private fun strategy(condition: String) =
        """
        STRATEGY spike VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1m
            news = HUB:test.news.MIDEAST EVERY 10m
        RULES
            WHEN $condition AND POSITION.gold = 0
            THEN BUY gold SIZING 1 EXIT AFTER 5m
        """.trimIndent()

    // The rule reads `known_at` first: its record stream used to be declared, and so delivered,
    // before the record's fields, which then read as older than their record and fed nothing.
    @Test
    fun `a z-score of a field ratio fires on the record that spikes it, whichever field moved`(
        @TempDir tmp: Path,
    ) = assertSpikes(tmp, "$fresh AND $spike")

    // The other order: the ratio used to be taken when `conflict` arrived, with the previous
    // record's `events`, so the spike made by `events` alone fired one record late.
    @Test
    fun `the same rule with the freshness test last gives the same fills`(
        @TempDir tmp: Path,
    ) = assertSpikes(tmp, "$spike AND $fresh")

    private fun assertSpikes(
        tmp: Path,
        condition: String,
    ) {
        // One record every 10 minutes from -1:00 (warmup) to 2:50, published 30 s into its slot.
        // Ratio 0.1 throughout except 0.5 at slot 3, 0.4 at slots 9 and 10 (conflict up) and 0.4 at
        // slot 15 (events down). Five-value z-scores, sample sd: 1.789 at slots 3, 9 and 15; 1.095
        // at slot 10, a second high value in a row; every other slot below zero.
        val conflict = mapOf(3 to "5", 9 to "4", 10 to "4")
        val events = mapOf(15 to "2.5")
        val records =
            (-6..17).map { k ->
                val known = t0.toEpochMilli() + k * 600_000L + 30_000L
                Rec("MIDEAST", known, mapOf("conflict" to (conflict[k] ?: "1"), "events" to (events[k] ?: "10")))
            }
        val root =
            HubFixtureStore(
                tmp,
            ).dataset("test.news", listOf(Field("conflict"), Field("events")), records).write()
        val end = t0.plusSeconds(3 * 3600)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), t0, end)
        val runs = HubScenarioHarness.run(root, strategy(condition), prices, t0, end)
        val expected = listOf(31L, 91L, 151L).flatMap { listOf(fill(it, "BUY"), fill(it + 5, "SELL")) }
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }
}
