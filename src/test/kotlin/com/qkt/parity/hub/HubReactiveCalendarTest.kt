package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A release blackout read from a schedule: each record names the next release (`at`) and is
 * published one minute after the previous release, as the hub's calendars are. The gate is
 * "no entry from 10 minutes before a release to 10 minutes after", using `cal.at` for the next
 * release and `cal.at[1]` for the one before it.
 */
class HubReactiveCalendarTest {
    private val t0 = Instant.parse("2024-03-05T00:00:00Z")

    private fun ms(minutes: Double) = t0.toEpochMilli() + (minutes * 60_000).toLong()

    private fun fill(
        minute: Long,
        side: String,
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), "BACKTEST:XAUUSD", side)

    private fun strategy(calWindow: String) =
        """
        STRATEGY blackout VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1m
            cal  = HUB:test.cal.USD EVERY $calWindow
        RULES
            WHEN (cal.at - NOW.epoch_ms > 600000 OR NOW.epoch_ms - cal.at > 600000)
                 AND NOW.epoch_ms - cal.at[1] > 600000
            THEN BUY gold SIZING 1 EXIT AFTER 1m
        """.trimIndent()

    /**
     * Releases at minutes 2, 60, 90 and 150 of the run's day, each named once the previous has
     * passed, after two earlier ones the live path needs as warmup (two bars of `cal` before start).
     */
    private fun store(tmp: Path): Path =
        HubFixtureStore(tmp)
            .dataset(
                "test.cal",
                listOf(Field("at")),
                listOf(
                    -3000.0 to -2990.0,
                    -200.0 to -100.0,
                    -99.0 to 2.0,
                    3.5 to 60.0,
                    61.5 to 90.0,
                    91.5 to 150.0,
                ).map { (known, at) ->
                    Rec("USD", ms(known), mapOf("at" to ms(at).toString()), key = "e$at")
                },
            ).write()

    private fun run(
        root: Path,
        calWindow: String,
    ): HubScenarioHarness.Runs {
        val end = t0.plusSeconds(3 * 3600)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), t0, end)
        return HubScenarioHarness.run(root, strategy(calWindow), prices, t0, end)
    }

    private val expected = listOf(13L, 71L, 101L, 161L).flatMap { listOf(fill(it, "BUY"), fill(it + 1, "SELL")) }

    @Test
    fun `a release blackout opens exactly at each window edge`(
        @TempDir tmp: Path,
    ) {
        // Minutes 1-12: release 2. 50-70: release 60 (50 is exactly 10 min before, 70 exactly 10 min
        // after; both ends blocked). 80-100: release 90. 140-160: release 150. The rule fires when
        // the gate opens.
        val runs = run(store(tmp), "1m")
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }

    @Test
    fun `index 1 is the previous record even when several share one bar of the stream`(
        @TempDir tmp: Path,
    ) {
        // On a daily stream releases 60, 90 and 150 are all named on the run's day. Were `[1]` the
        // previous day's bar it would read release 2 all day and let entries in at minutes 62 and
        // 92, inside the after-windows of releases 60 and 90.
        val runs = run(store(tmp), "1d")
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
    }
}
