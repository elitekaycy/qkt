package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #77 — Phase 40 Task 10. End-to-end: DSL `SCHEDULE` → AstCompiler →
 * TradingPipeline → ScheduleRunner heartbeat → Backtest trade emission. The
 * pipeline ticks the runner on every tick replay; firing semantics match
 * what live would produce given the same tick stream.
 */
class ScheduleEndToEndTest {
    private val mondayMidnightUtc: Long =
        LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun compile(src: String) =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    private fun tick(
        ts: Long,
        price: String = "100",
    ): Tick =
        Tick(
            symbol = "BACKTEST:GOLD",
            price = Money.of(price),
            timestamp = ts,
        )

    @Test
    fun `SCHEDULE AT 09 colon 00 UTC fires the action once per day`() {
        val ticks =
            (0L..30L).map { hr ->
                tick(mondayMidnightUtc + hr * hour, "1000")
            }

        val result =
            Backtest(
                strategies =
                    listOf(
                        "sched" to
                            compile(
                                """
                                STRATEGY sched VERSION 1
                                SYMBOLS
                                  gold = BACKTEST:GOLD EVERY 1m
                                SCHEDULE
                                  AT 09:00 UTC THEN BUY gold SIZING 0.1
                                RULES
                                  WHEN gold.close > 999999 THEN BUY gold SIZING 0.1
                                """.trimIndent(),
                            ),
                    ),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        // 30 hours = one 09:00 + one next-day 09:00 (hour 33 isn't in range, so just 1+1).
        // Range is 0..30 inclusive = 31 ticks at hours 0..30.
        // 09:00 day 1 (hour 9) and 09:00 day 2 (hour 33) — hour 33 is out of range.
        // So 1 fire expected.
        assertThat(buys).hasSize(1)
    }

    @Test
    fun `SCHEDULE multi-time AT list fires once per listed time per day`() {
        val ticks =
            (0L..20L).map { hr ->
                tick(mondayMidnightUtc + hr * hour, "1000")
            }

        val result =
            Backtest(
                strategies =
                    listOf(
                        "sched" to
                            compile(
                                """
                                STRATEGY sched VERSION 1
                                SYMBOLS
                                  gold = BACKTEST:GOLD EVERY 1m
                                SCHEDULE
                                  AT 09:00, 12:00, 14:00 UTC THEN BUY gold SIZING 0.1
                                RULES
                                  WHEN gold.close > 999999 THEN BUY gold SIZING 0.1
                                """.trimIndent(),
                            ),
                    ),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        // 0..20 hours covers 09:00, 12:00, 14:00 once each.
        assertThat(buys).hasSize(3)
    }

    @Test
    fun `SCHEDULE EVERY HOUR AT colon 00 fires once per hour`() {
        val ticks =
            (0L..4L).map { hr ->
                tick(mondayMidnightUtc + hr * hour + 30 * 60_000L, "1000") // 00:30, 01:30, 02:30, 03:30, 04:30
            }

        val result =
            Backtest(
                strategies =
                    listOf(
                        "sched" to
                            compile(
                                """
                                STRATEGY sched VERSION 1
                                SYMBOLS
                                  gold = BACKTEST:GOLD EVERY 1m
                                SCHEDULE
                                  EVERY HOUR AT :00 THEN BUY gold SIZING 0.1
                                RULES
                                  WHEN gold.close > 999999 THEN BUY gold SIZING 0.1
                                """.trimIndent(),
                            ),
                    ),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        // Ticks: 00:30, 01:30, 02:30, 03:30, 04:30.
        // First tick @ 00:30: schedule heartbeat crosses 00:00 :00 but no bar has
        //   closed yet (warmup-cold) → fire skipped with WARN.
        // Each subsequent tick closes a 1m bar from the previous tick's window, so
        //   `latestKnownCandle` returns it and the schedule fires emit a BUY.
        // Result: 4 BUYs for ticks 2-5.
        assertThat(buys).hasSize(4)
    }
}
