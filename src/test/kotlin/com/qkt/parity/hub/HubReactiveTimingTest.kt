package com.qkt.parity.hub

import com.qkt.marketdata.hub.HubPolicy
import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * When a hub fact becomes actionable: never before its `known_at` (plus any configured lag), on the
 * first evaluation after it, and from the first bar of a session that starts after it was published.
 */
class HubReactiveTimingTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")

    private fun ms(minutes: Long) = t0.toEpochMilli() + minutes * 60_000L

    private fun fill(
        minute: Long,
        side: String,
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), "BACKTEST:XAUUSD", side)

    private val onRecord =
        """
        STRATEGY onRecord VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1m
            m    = HUB:test.macro.USD EVERY 1d
        RULES
            WHEN NOW.epoch_ms - m.known_at <= 120000 AND m.value > 0 AND POSITION.gold = 0
            THEN BUY gold SIZING 1 EXIT AFTER 5m
        """.trimIndent()

    private fun run(
        root: Path,
        strategy: String = onRecord,
        from: Instant = t0,
        policy: HubPolicy = HubPolicy(),
    ): HubScenarioHarness.Runs {
        val end = t0.plusSeconds(3 * 3600)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), from, end)
        return HubScenarioHarness.run(root, strategy, prices, from, end, policy = policy)
    }

    private fun assertAllPaths(
        runs: HubScenarioHarness.Runs,
        expected: List<HubScenarioHarness.Fill>,
    ) {
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }

    private fun store(
        tmp: Path,
        vararg records: Rec,
    ): Path = HubFixtureStore(tmp).dataset("test.macro", listOf(Field("value")), records.toList()).write()

    @Test
    fun `a fact published on a price tick's millisecond is acted on at the next evaluation`(
        @TempDir tmp: Path,
    ) {
        // The 0:40:00 price tick is processed before the fact sharing its millisecond, so the fact
        // is first evaluated at the bar closing 0:41:00, and filled there.
        assertAllPaths(
            run(store(tmp, Rec("USD", ms(40), mapOf("value" to "1")))),
            listOf(fill(41, "BUY"), fill(46, "SELL")),
        )
    }

    @Test
    fun `the price tick still goes first when the hub stream is declared before the market`(
        @TempDir tmp: Path,
    ) {
        // The tie on 0:40:00 must not depend on the order of SYMBOLS.
        val hubFirst =
            onRecord.replace(
                "    gold = BACKTEST:XAUUSD EVERY 1m\n    m    = HUB:test.macro.USD EVERY 1d",
                "    m    = HUB:test.macro.USD EVERY 1d\n    gold = BACKTEST:XAUUSD EVERY 1m",
            )
        assertThat(hubFirst).isNotEqualTo(onRecord)
        assertAllPaths(
            run(store(tmp, Rec("USD", ms(40), mapOf("value" to "1"))), strategy = hubFirst),
            listOf(fill(41, "BUY"), fill(46, "SELL")),
        )
    }

    @Test
    fun `a configured minimum lag delays every decision by exactly the lag`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp, Rec("USD", ms(40) + 30_000L, mapOf("value" to "1")))
        assertAllPaths(run(root), listOf(fill(41, "BUY"), fill(46, "SELL")))
        assertAllPaths(run(root, policy = HubPolicy(minLagMs = 600_000L)), listOf(fill(51, "BUY"), fill(56, "SELL")))
    }

    @Test
    fun `a fact describing the past but published later is invisible until it is published`(
        @TempDir tmp: Path,
    ) {
        // A value effective at 0:10 that the source only published at 1:30:30.
        val root = store(tmp, Rec("USD", ms(90) + 30_000L, mapOf("value" to "1"), effectiveAt = ms(10)))
        assertAllPaths(run(root), listOf(fill(91, "BUY"), fill(96, "SELL")))
    }

    @Test
    fun `a revision is acted on at its own known_at with its own value`(
        @TempDir tmp: Path,
    ) {
        // First published negative (no trade), revised to positive an hour later (trade then).
        val root =
            store(
                tmp,
                Rec("USD", ms(20) + 30_000L, mapOf("value" to "-1"), key = "k"),
                Rec("USD", ms(80) + 30_000L, mapOf("value" to "1"), key = "k", revision = 2),
            )
        assertAllPaths(run(root), listOf(fill(81, "BUY"), fill(86, "SELL")))
    }

    @Test
    fun `refused derived records are never seen and observed ones are`(
        @TempDir tmp: Path,
    ) {
        val root =
            store(
                tmp,
                Rec("USD", ms(20) + 30_000L, mapOf("value" to "1"), availability = "derived"),
                Rec("USD", ms(80) + 30_000L, mapOf("value" to "1"), availability = "observed"),
            )
        assertAllPaths(run(root), listOf(fill(21, "BUY"), fill(26, "SELL"), fill(81, "BUY"), fill(86, "SELL")))
        val refused = run(root, policy = HubPolicy(refuseDerived = true))
        assertThat(refused.ticks).containsExactly(fill(81, "BUY"), fill(86, "SELL"))
        assertThat(refused.bars).isEqualTo(refused.ticks)
    }

    @Test
    fun `a session starting in the afternoon reads the morning's record and the one before it`(
        @TempDir tmp: Path,
    ) {
        // Yesterday 1.0, this morning 2.0; the session opens at 15:00. A daily stream's history
        // must include this morning's record: it was published seven hours before the start.
        val day = Instant.parse("2024-03-05T00:00:00Z").toEpochMilli()
        val root =
            store(
                tmp,
                Rec("USD", day - 86_400_000L + 8 * 3_600_000L + 1_800_000L, mapOf("value" to "1.0")),
                Rec("USD", day + 8 * 3_600_000L + 1_800_000L, mapOf("value" to "2.0")),
            )
        val strategy =
            """
            STRATEGY afternoon VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                m    = HUB:test.macro.USD EVERY 1d
            RULES
                WHEN m.value = 2 AND m.value[1] = 1 AND POSITION.gold = 0
                THEN BUY gold SIZING 1
            """.trimIndent()
        val from = Instant.parse("2024-03-05T15:00:00Z")
        val end = from.plusSeconds(3600)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), from, end)
        val expected = listOf(HubScenarioHarness.Fill(from.plusSeconds(60), "BACKTEST:XAUUSD", "BUY"))
        assertAllPaths(HubScenarioHarness.run(root, strategy, prices, from, end), expected)
    }
}
