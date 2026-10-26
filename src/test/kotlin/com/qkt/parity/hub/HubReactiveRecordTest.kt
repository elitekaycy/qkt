package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Strategies that react to hub records themselves: a new value arriving, the fields of one record
 * read together, and a field the record leaves unknown. Every scenario states its expected trades
 * by hand and requires the tick backtest, the bar tier and the live path to produce exactly them.
 */
class HubReactiveRecordTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")

    private fun at(minutes: Long) = t0.toEpochMilli() + minutes * 60_000L + 30_000L // between price ticks

    private fun fill(
        minute: Long,
        side: String,
        symbol: String = "BACKTEST:XAUUSD",
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), symbol, side)

    private fun run(
        root: Path,
        strategy: String,
    ): HubScenarioHarness.Runs {
        val end = t0.plusSeconds(3 * 3600)
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
    fun `a rising value is acted on the first price after its record arrives, and a repeat is not`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.macro",
                    listOf(Field("value")),
                    // Two records the day before seed the lookback; the window starts at 1.0.
                    listOf(-1440L to "0.5", -720L to "1.0", 40L to "2.0", 70L to "2.0", 100L to "3.0").map { (m, v) ->
                        Rec("USD", at(m), mapOf("value" to v))
                    },
                ).write()
        val strategy =
            """
            STRATEGY onArrival VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                m    = HUB:test.macro.USD EVERY 1d
            RULES
                WHEN NOW.epoch_ms - m.known_at < 60000 AND m.value > m.value[1] AND POSITION.gold = 0
                THEN BUY gold SIZING 1 EXIT AFTER 5m
            """.trimIndent()
        // `m.value > m.value[1]` alone is a level: true from the first bar, because the value rose the
        // day before. Reacting means "a record just arrived and it rose": records at :40:30 and
        // 1:40:30 do; the repeat at 1:10:30 does not. The next price tick fills, the exit 5m later.
        assertAllPaths(
            run(root, strategy),
            listOf(fill(41, "BUY"), fill(46, "SELL"), fill(101, "BUY"), fill(106, "SELL")),
        )
    }

    @Test
    fun `known_at reacts to every record, even one that repeats the last value`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.macro",
                    listOf(Field("value")),
                    listOf(40L, 70L, 100L).map { m -> Rec("USD", at(m), mapOf("value" to "2.0")) },
                ).write()
        val strategy =
            """
            STRATEGY onRecord VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                m    = HUB:test.macro.USD EVERY 1d
            RULES
                WHEN NOW.epoch_ms - m.known_at < 60000 AND m.value > 1 AND POSITION.gold = 0
                THEN BUY gold SIZING 1 EXIT AFTER 5m
            """.trimIndent()
        // Each record is fresh for one minute after it is known: the bar closing at :41, 1:11 and 1:41.
        assertAllPaths(
            run(root, strategy),
            listOf(41L, 71L, 101L).flatMap { listOf(fill(it, "BUY"), fill(it + 5, "SELL")) },
        )
    }

    @Test
    fun `two scopes of one dataset each drive their own symbol`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.release",
                    listOf(Field("surprise")),
                    listOf(
                        Rec("USD", at(20), mapOf("surprise" to "1.0")),
                        Rec("EUR", at(50), mapOf("surprise" to "1.0")),
                    ),
                ).write()
        val strategy =
            """
            STRATEGY twoScopes VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                eur  = BACKTEST:EURUSD EVERY 1m
                us   = HUB:test.release.USD EVERY 1d
                eu   = HUB:test.release.EUR EVERY 1d
            RULES
                WHEN NOW.epoch_ms - us.known_at < 60000 AND us.surprise > 0 AND POSITION.gold = 0
                THEN SELL gold SIZING 1 EXIT AFTER 5m
                WHEN NOW.epoch_ms - eu.known_at < 60000 AND eu.surprise > 0 AND POSITION.eur = 0
                THEN BUY eur SIZING 1 EXIT AFTER 5m
            """.trimIndent()
        val end = t0.plusSeconds(2 * 3600)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000", "BACKTEST:EURUSD" to "1.08"), t0, end)
        assertAllPaths(
            HubScenarioHarness.run(root, strategy, prices, t0, end),
            listOf(
                fill(21, "SELL"),
                fill(26, "BUY"),
                fill(51, "BUY", "BACKTEST:EURUSD"),
                fill(56, "SELL", "BACKTEST:EURUSD"),
            ),
        )
    }

    @Test
    fun `the fields of one record are never read half updated`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.pair",
                    listOf(Field("a"), Field("b")),
                    (1L..6L).map { i -> Rec("USD", at(i * 15), mapOf("a" to "$i", "b" to "$i")) },
                ).write()
        val strategy =
            """
            STRATEGY torn VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                p    = HUB:test.pair.USD EVERY 1d
            RULES
                WHEN p.a != p.b AND POSITION.gold = 0
                THEN BUY gold SIZING 1 EXIT AFTER 1m
            """.trimIndent()
        assertAllPaths(run(root, strategy), emptyList())
    }

    @Test
    fun `a field the newest record leaves unknown does not show the previous record's value`(
        @TempDir tmp: Path,
    ) {
        // Release 1 is known with its actual; release 2 is announced with a forecast and no actual
        // yet; its actual arrives later as revision 2 of the same record.
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.release",
                    listOf(Field("actual"), Field("forecast")),
                    listOf(
                        Rec("USD", at(10), mapOf("actual" to "0.5", "forecast" to "0.3"), key = "r1"),
                        Rec("USD", at(30), mapOf("actual" to null, "forecast" to "0.4"), key = "r2"),
                        Rec("USD", at(60), mapOf("actual" to "0.6", "forecast" to "0.4"), key = "r2", revision = 2),
                    ),
                ).write()
        val strategy =
            """
            STRATEGY beat VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                r    = HUB:test.release.USD EVERY 1d
            RULES
                WHEN r.actual > r.forecast AND POSITION.gold = 0
                THEN BUY gold SIZING 1 EXIT AFTER 2m
            """.trimIndent()
        // The rule holds while release 1 beats (entries every three minutes: fill, exit two minutes
        // later, re-enter on the next bar). While release 2's actual is unknown, 0:30:30 to 1:00:30,
        // nothing beats anything: release 1's 0.5 must not be compared with release 2's 0.4 forecast.
        val entries = (11L..29L step 3) + (61L..179L step 3)
        val expected =
            entries.flatMap { listOf(fill(it, "BUY"), fill(it + 2, "SELL")) }.filter {
                it.at <
                    t0.plusSeconds(180 * 60)
            }
        assertAllPaths(run(root, strategy), expected)
    }
}
