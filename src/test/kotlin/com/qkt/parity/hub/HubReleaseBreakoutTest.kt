package com.qkt.parity.hub

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * brk_xau exactly as measured (docs/research, release strategies, 2026-10-26): on the first minute
 * closing after a US release, a close beyond the 15-minute pre-release range goes with the break for
 * an hour. The release calendar names each release once the previous one has passed, a minute after it.
 */
class HubReleaseBreakoutTest {
    private val t0 = Instant.parse("2024-03-05T12:00:00Z")

    private fun ms(minutes: Int) = t0.toEpochMilli() + minutes * 60_000L

    private val strategy =
        """
        STRATEGY brk_xau VERSION 1
        SYMBOLS
            x   = BACKTEST:XAUUSD EVERY 1m,
            cal = HUB:cal.us_high.USD EVERY 1d
        RULES
            -- On the first minute closing after a US release, break of the 15-minute pre-release range; hold an hour.
            WHEN NOW.epoch_ms - cal.at[1] >= 60000 AND NOW.epoch_ms - cal.at[1] < 120000 AND x.close > lag(highest(x.high, 15), 1) AND POSITION.x = 0
            THEN BUY x SIZING 1 EXIT AFTER 60m
            WHEN NOW.epoch_ms - cal.at[1] >= 60000 AND NOW.epoch_ms - cal.at[1] < 120000 AND x.close < lag(lowest(x.low, 15), 1) AND POSITION.x = 0
            THEN SELL x SIZING 1 EXIT AFTER 60m
        """.trimIndent()

    @Test
    fun `the first close after a release breaks the range up, the next release down, and a third stays inside`(
        @TempDir tmp: Path,
    ) {
        // Releases at minutes 30, 120 and 200. Gold alternates 2000/2001 (range 2000-2001); the minute
        // of each release prints 2005 (up), 1990 (down) and 2000.5 (inside). One tick a minute, 200 ms
        // in, as recorded ticks land after the minute; the release minute's bar closes on the next one.
        val releases = listOf(-6000, -3000, 30, 120, 200, 300) // two before the start: `at[1]` warms
        val records =
            releases.zipWithNext().map { (previous, next) ->
                Rec("USD", ms(previous + 1), mapOf("at" to ms(next).toString()), key = "e$next")
            }
        val root = HubFixtureStore(tmp).dataset("cal.us_high", listOf(Field("at")), records).write()
        val spike = mapOf(30 to "2005", 120 to "1990", 200 to "2000.5")
        val prices =
            (-30 until 260).map { m ->
                // half an hour before the start is the range's warmup
                Tick("BACKTEST:XAUUSD", Money.of(spike[m] ?: if (m % 2 == 0) "2000" else "2001"), ms(m) + 200)
            }
        val end = t0.plusSeconds(260 * 60L)
        val runs = HubScenarioHarness.run(root, strategy, prices, t0, end)

        fun fill(
            minute: Int,
            side: String,
        ) = HubScenarioHarness.Fill(Instant.ofEpochMilli(ms(minute) + 200), "BACKTEST:XAUUSD", side)
        val expected = listOf(fill(31, "BUY"), fill(91, "SELL"), fill(121, "SELL"), fill(181, "BUY"))
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(expected)
        // The bar tier cannot see when a minute's first trade came. Its release-minute bar closes on the
        // next bar's open at exactly release + 60 s, the record's own millisecond, and the price goes
        // first; the next close is 120 s after, outside the rule's window. So --bars never trades this
        // rule: research release-minute rules on ticks (docs/reference/dsl/hub.md).
        assertThat(runs.bars).describedAs("bar tier").isEmpty()
    }
}
