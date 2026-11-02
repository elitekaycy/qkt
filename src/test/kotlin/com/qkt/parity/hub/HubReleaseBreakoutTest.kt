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
 * brk_xau, brk_eur and brk_btc exactly as measured (docs/research, release strategies, 2026-10-26): on
 * the first minute closing after a US release, a close beyond the 15-minute pre-release range goes
 * with the break for an hour. The release calendar names each release a minute after the previous one.
 */
class HubReleaseBreakoutTest {
    private val t0 = Instant.parse("2024-03-05T12:00:00Z")

    private fun ms(minutes: Int) = t0.toEpochMilli() + minutes * 60_000L

    private fun strategy(
        name: String,
        symbol: String,
    ) = """
        STRATEGY $name VERSION 1
        SYMBOLS
            x   = $symbol EVERY 1m,
            cal = HUB:cal.us_high.USD EVERY 1d
        RULES
            -- On the first minute closing after a US release, break of the 15-minute pre-release range; hold an hour.
            WHEN NOW.epoch_ms - cal.at[1] >= 60000 AND NOW.epoch_ms - cal.at[1] < 120000 AND x.close > lag(highest(x.high, 15), 1) AND POSITION.x = 0
            THEN BUY x SIZING 1 EXIT AFTER 60m
            WHEN NOW.epoch_ms - cal.at[1] >= 60000 AND NOW.epoch_ms - cal.at[1] < 120000 AND x.close < lag(lowest(x.low, 15), 1) AND POSITION.x = 0
            THEN SELL x SIZING 1 EXIT AFTER 60m
        """.trimIndent()

    /**
     * Releases at minutes 30, 120 and 200 (two earlier ones warm `at[1]`). Prices alternate low and
     * high; the minute of each release prints up, down and inside the range. One tick a minute, 200 ms
     * in, as recorded ticks land after the minute. Ticks and live fill on the tick after the release
     * minute's close; the bar tier, which has no sub-minute times, fills on the next bar's open.
     */
    private fun assertBreakouts(
        tmp: Path,
        name: String,
        symbol: String,
        prices: List<String>,
    ) {
        val (low, high, up, down, inside) = prices
        val releases = listOf(-6000, -3000, 30, 120, 200, 300)
        val records =
            releases.zipWithNext().map { (previous, next) ->
                Rec("USD", ms(previous + 1), mapOf("at" to ms(next).toString()), key = "e$next")
            }
        val root = HubFixtureStore(tmp).dataset("cal.us_high", listOf(Field("at")), records).write()
        val spike = mapOf(30 to up, 120 to down, 200 to inside)
        val tape =
            (-30 until 260).map { m ->
                // half an hour before the start is the range's warmup
                Tick(symbol, Money.of(spike[m] ?: if (m % 2 == 0) low else high), ms(m) + 200)
            }
        val runs = HubScenarioHarness.run(root, strategy(name, symbol), tape, t0, t0.plusSeconds(260 * 60L))

        fun fills(offsetMs: Long) =
            listOf(31 to "BUY", 91 to "SELL", 121 to "SELL", 181 to "BUY").map { (m, side) ->
                HubScenarioHarness.Fill(Instant.ofEpochMilli(ms(m) + offsetMs), symbol, side)
            }
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(fills(200))
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(fills(200))
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(fills(0))
    }

    @Test
    fun `brk_xau breaks up after one release, down after the next, and not inside the range`(
        @TempDir tmp: Path,
    ) = assertBreakouts(tmp, "brk_xau", "BACKTEST:XAUUSD", listOf("2000", "2001", "2005", "1990", "2000.5"))

    @Test
    fun `brk_eur breaks up after one release, down after the next, and not inside the range`(
        @TempDir tmp: Path,
    ) = assertBreakouts(tmp, "brk_eur", "BACKTEST:EURUSD", listOf("1.0800", "1.0801", "1.0805", "1.0790", "1.08005"))

    @Test
    fun `brk_btc breaks up after one release, down after the next, and not inside the range`(
        @TempDir tmp: Path,
    ) = assertBreakouts(tmp, "brk_btc", "BACKTEST:BTCUSD", listOf("60000", "60010", "60050", "59900", "60005"))
}
