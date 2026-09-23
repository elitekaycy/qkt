package com.qkt.parity

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslExitAfterParityTest {
    private val symbol = "BACKTEST:BTCUSDT"
    private val t0 = 1_700_000_040_000L
    private val step = 10_000L

    private fun tape(vararg prices: String): List<Tick> =
        prices.mapIndexed { i, p -> Tick(symbol, Money.of(p), t0 + i * step) }

    private fun firstTickAtOrAfter(
        ticks: List<Tick>,
        ts: Long,
    ): Long = ticks.first { it.timestamp >= ts }.timestamp

    private fun run(
        id: String,
        source: String,
        ticks: List<Tick>,
    ): DslParityHarness.Result {
        val result = DslParityHarness.run(id, source, ticks)
        assertThat(result.live).isEqualTo(result.backtest)
        return result
    }

    @Test
    fun `bracket-less entry closes on the first tick at or after fill plus the hold`() {
        val ticks = tape(*Array(7) { "100" }, *Array(20) { "101" })
        val result =
            run(
                "timed",
                """
                STRATEGY timed VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc SIZING 1 EXIT AFTER 90s
                """.trimIndent(),
                ticks,
            )

        val (entry, exit) = result.backtest.trades
        assertThat(result.backtest.trades).hasSize(2)
        assertThat(entry.side).isEqualTo("BUY")
        assertThat(exit.side).isEqualTo("SELL")
        assertThat(exit.timestamp).isEqualTo(firstTickAtOrAfter(ticks, entry.timestamp + 90_000L))
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `timed exit cancels the bracket so its exits cannot fire afterwards`() {
        val ticks = tape(*Array(7) { "100" }, *Array(12) { "101" }, "200", "200", "1", "1")
        val result =
            run(
                "timed_bracket",
                """
                STRATEGY timed_bracket VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc SIZING 1 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 } EXIT AFTER 90s
                """.trimIndent(),
                ticks,
            )

        assertThat(result.backtest.trades).hasSize(2)
        val (entry, exit) = result.backtest.trades
        assertThat(exit.timestamp).isEqualTo(firstTickAtOrAfter(ticks, entry.timestamp + 90_000L))
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `an earlier take-profit drops the timer instead of opening a counter position`() {
        val ticks = tape(*Array(7) { "100" }, "106", *Array(20) { "101" })
        val result =
            run(
                "timed_tp",
                """
                STRATEGY timed_tp VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc SIZING 1 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 5 } EXIT AFTER 90s
                """.trimIndent(),
                ticks,
            )

        assertThat(result.backtest.trades).hasSize(2)
        assertThat(
            result.backtest.trades
                .last()
                .timestamp,
        ).isEqualTo(t0 + 7 * step)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `each STACK_AT leg closes on its own fill plus the hold`() {
        val ticks = tape(*Array(7) { "100" }, "100", "100", "106", *Array(20) { "106" })
        val result =
            run(
                "timed_stack",
                """
                STRATEGY timed_stack VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc SIZING 1
                    STACK_AT MFE >= 5 WITHIN 30m SIZING 0.5 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    EXIT AFTER 90s
                """.trimIndent(),
                ticks,
            )

        val trades = result.backtest.trades
        assertThat(trades).hasSize(4)
        val primaryOpen = trades.first { it.side == "BUY" && it.quantity == "1" }
        val stackOpen = trades.first { it.side == "BUY" && it.quantity == "0.5" }
        val primaryClose = trades.first { it.side == "SELL" && it.quantity == "1" }
        val stackClose = trades.first { it.side == "SELL" && it.quantity == "0.5" }
        assertThat(stackOpen.timestamp).isGreaterThan(primaryOpen.timestamp)
        assertThat(primaryClose.timestamp).isEqualTo(firstTickAtOrAfter(ticks, primaryOpen.timestamp + 90_000L))
        assertThat(stackClose.timestamp).isEqualTo(firstTickAtOrAfter(ticks, stackOpen.timestamp + 90_000L))
        assertThat(result.backtest.positions).isEmpty()
    }
}
