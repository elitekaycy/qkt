package com.qkt.parity

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A `holding_duration` time exit closes on the bar where the hold has elapsed on the bar clock,
 * whether the entry filled exactly on the bar boundary (bar backtest) or a few seconds after it
 * (live and tick replay, where the first executable price follows the close), #1633.
 */
class HoldingDurationBarClockParityTest {
    private val symbol = "BACKTEST:BTCUSDT"

    // A minute boundary: bars are [t0 + k*60s, t0 + (k+1)*60s).
    private val t0 = 1_700_000_040_000L
    private val step = 10_000L

    private val source =
        """
        STRATEGY timeexit VERSION 1
        SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
        RULES
          WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc SIZING 1
          WHEN POSITION.btc != 0 AND POSITION.btc.holding_duration >= 120 THEN CLOSE btc
        """.trimIndent()

    /** One bar at 100 (the entry signal), then 101 for long enough to see the exit. */
    private fun tape(offsetMs: Long): List<Tick> =
        (0 until 40).map { i ->
            Tick(symbol, Money.of(if (i < 6) "100" else "101"), t0 + offsetMs + i * step)
        }

    private fun run(ticks: List<Tick>): DslParityHarness.Result {
        val result = DslParityHarness.run("timeexit", source, ticks)
        assertThat(result.live).isEqualTo(result.backtest)
        return result
    }

    @Test
    fun `an entry filled seconds after the bar close exits on the bar where the hold elapsed`() {
        // The entry signal is the bar closing at t0+60s; its first executable price is 2s later.
        val result = run(tape(offsetMs = 2_000L))

        val (entry, exit) = result.backtest.trades
        assertThat(result.backtest.trades).hasSize(2)
        assertThat(entry.timestamp).isEqualTo(t0 + 62_000L)
        // Two 1m bars after the entry bar closes at t0+180s; the close fills on the next tick.
        assertThat(exit.side).isEqualTo("SELL")
        assertThat(exit.timestamp).isEqualTo(t0 + 182_000L)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `an entry filled on the bar boundary keeps its exit bar`() {
        // Bar-backtest shape: the fill is stamped on the boundary that closed the signal bar.
        val result = run(tape(offsetMs = 0L))

        val (entry, exit) = result.backtest.trades
        assertThat(result.backtest.trades).hasSize(2)
        assertThat(entry.timestamp).isEqualTo(t0 + 60_000L)
        assertThat(exit.timestamp).isEqualTo(t0 + 180_000L)
        assertThat(result.backtest.positions).isEmpty()
    }
}
