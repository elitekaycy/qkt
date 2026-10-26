package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Where a hub fact goes once it is seen: to several markets at once, through a rule combining
 * datasets, into an indicator and against a scheduled event.
 */
class HubReactiveRoutingTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")
    private val gold = "BACKTEST:XAUUSD"

    private fun ms(minutes: Long) = t0.toEpochMilli() + minutes * 60_000L + 30_000L

    private fun fill(
        minute: Long,
        side: String,
        symbol: String = gold,
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), symbol, side)

    private fun assertAllPaths(
        runs: HubScenarioHarness.Runs,
        expected: List<HubScenarioHarness.Fill>,
    ) {
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }

    private fun run(
        root: Path,
        strategy: String,
        prices: Map<String, String> = mapOf(gold to "2000"),
    ): HubScenarioHarness.Runs {
        val end = t0.plusSeconds(3 * 3600)
        return HubScenarioHarness.run(root, strategy, HubScenarioHarness.tape(prices, t0, end), t0, end)
    }

    @Test
    fun `one hot US release sells gold, sells EURUSD and sells bitcoin on the same bar`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.release",
                    listOf(Field("surprise")),
                    listOf(Rec("USD", ms(20), mapOf("surprise" to "0.3"))),
                ).write()
        val strategy =
            """
            STRATEGY hotPrint VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                eur  = BACKTEST:EURUSD EVERY 1m
                btc  = BACKTEST:BTCUSD EVERY 1m
                cpi  = HUB:test.release.USD EVERY 1d
            RULES
                WHEN NOW.epoch_ms - cpi.known_at <= 60000 AND cpi.surprise > 0.2 AND POSITION.gold = 0
                THEN SELL gold SIZING 1 EXIT AFTER 10m
                WHEN NOW.epoch_ms - cpi.known_at <= 60000 AND cpi.surprise > 0.2 AND POSITION.eur = 0
                THEN SELL eur SIZING 1 EXIT AFTER 10m
                WHEN NOW.epoch_ms - cpi.known_at <= 60000 AND cpi.surprise > 0.2 AND POSITION.btc = 0
                THEN SELL btc SIZING 1 EXIT AFTER 10m
            """.trimIndent()
        val runs = run(root, strategy, mapOf(gold to "2000", "BACKTEST:EURUSD" to "1.08", "BACKTEST:BTCUSD" to "60000"))
        val symbols = listOf(gold, "BACKTEST:EURUSD", "BACKTEST:BTCUSD")
        val expected = symbols.map { fill(21, "SELL", it) } + symbols.map { fill(31, "BUY", it) }
        for ((name, fills) in listOf("ticks" to runs.ticks, "bars" to runs.bars, "live" to runs.live.orEmpty())) {
            assertThat(fills).describedAs(name).containsExactlyInAnyOrderElementsOf(expected)
        }
    }

    @Test
    fun `a rate signal is taken only outside the half hour before the next scheduled release`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.cal",
                    listOf(Field("at", type = "timestamp")),
                    listOf(
                        Rec("USD", t0.toEpochMilli() - 86_400_000L, mapOf("at" to "#${ms(60) - 30_000L}")),
                        Rec("USD", ms(61) - 30_000L + 60_000L, mapOf("at" to "#${ms(180) - 30_000L}")),
                    ),
                ).dataset(
                    "test.rate",
                    listOf(Field("value")),
                    listOf(20L, 40L, 80L).map { Rec("USD", ms(it), mapOf("value" to "0.5")) },
                ).write()
        val strategy =
            """
            STRATEGY gated VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                cal  = HUB:test.cal.USD EVERY 1d
                r    = HUB:test.rate.USD EVERY 1d
            RULES
                WHEN NOW.epoch_ms - r.known_at <= 60000 AND r.value < 1 AND cal.at - NOW.epoch_ms > 1800000 AND POSITION.gold = 0
                THEN BUY gold SIZING 1 EXIT AFTER 5m
            """.trimIndent()
        // 0:20:30 trades (release at 1:00 is 39 min away); 0:40:30 does not (19 min); 1:20:30 trades
        // against the next release at 3:00, announced at 1:01.
        assertAllPaths(
            run(root, strategy),
            listOf(fill(21, "BUY"), fill(26, "SELL"), fill(81, "BUY"), fill(86, "SELL")),
        )
    }

    @Test
    fun `an indicator over a hub field crosses on the record that moves it`(
        @TempDir tmp: Path,
    ) {
        val history = (3L downTo 1L).map { d -> Rec("USD", t0.toEpochMilli() - d * 86_400_000L, mapOf("value" to "0")) }
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.macro",
                    listOf(Field("value")),
                    history + listOf(20L, 40L, 60L).map { Rec("USD", ms(it), mapOf("value" to "1.5")) },
                ).write()
        val strategy =
            """
            STRATEGY smaCross VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                m    = HUB:test.macro.USD EVERY 1d
            RULES
                WHEN sma(m.value, 3) CROSSES ABOVE 1 AND POSITION.gold = 0
                THEN BUY gold SIZING 1 EXIT AFTER 5m
            """.trimIndent()
        // sma over the last three records: 0.5, 1.0, then 1.5 at 1:00:30, the only crossing above 1.
        assertAllPaths(run(root, strategy), listOf(fill(61, "BUY"), fill(66, "SELL")))
    }

    @Test
    fun `a position is flattened fifteen minutes before a scheduled release`(
        @TempDir tmp: Path,
    ) {
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.cal",
                    listOf(Field("at", type = "timestamp")),
                    listOf(
                        Rec(
                            "USD",
                            t0.toEpochMilli() - 86_400_000L,
                            mapOf(
                                "at" to "#${t0.toEpochMilli() + 3_600_000L}",
                            ),
                        ),
                    ),
                ).write()
        val strategy =
            """
            STRATEGY flatBefore VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                cal  = HUB:test.cal.USD EVERY 1d
            RULES
                WHEN cal.at - NOW.epoch_ms > 1800000 AND POSITION.gold = 0
                THEN BUY gold SIZING 1
                WHEN cal.at - NOW.epoch_ms <= 900000 AND POSITION.gold != 0
                THEN CLOSE gold
            """.trimIndent()
        // In at the first bar (release at 1:00 is an hour away), out on the bar closing at 0:45.
        assertAllPaths(run(root, strategy), listOf(fill(1, "BUY"), fill(45, "SELL")))
    }
}
