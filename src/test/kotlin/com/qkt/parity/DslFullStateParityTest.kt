package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.parity.BacktestLiveParityFixtures.initialTs
import com.qkt.parity.BacktestLiveParityFixtures.symbol
import com.qkt.strategy.WarmupStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslFullStateParityTest {
    @Test
    fun `compiled candle indicator bracket has full-state parity`() {
        val dsl =
            """
            STRATEGY candle_bracket VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN ema(btc.close, 2) CROSSES ABOVE ema(btc.close, 3)
              THEN BUY btc BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 3 }
              WHEN ema(btc.close, 2) CROSSES BELOW ema(btc.close, 3)
              THEN CLOSE btc
            """.trimIndent()
        val prices = listOf("100", "99", "98", "99", "101", "104", "103", "101", "98", "97", "97")
        val tape =
            prices.mapIndexed { index, price ->
                Tick("BACKTEST:BTCUSDT", Money.of(price), initialTs + index * 60_000L)
            }
        val firstWindowStart = initialTs - Math.floorMod(initialTs, 60_000L)
        val warmupCandles =
            (3 downTo 1).map { offset ->
                Candle(
                    symbol = "BACKTEST:BTCUSDT",
                    open = BigDecimal("100"),
                    high = BigDecimal("100"),
                    low = BigDecimal("100"),
                    close = BigDecimal("100"),
                    volume = BigDecimal.ZERO,
                    startTime = firstWindowStart - offset * 60_000L,
                    endTime = firstWindowStart - (offset - 1) * 60_000L,
                )
            }

        val result = DslParityHarness.run("candle_bracket", dsl, tape, warmupCandles)

        assertThat(result.backtest.trades).isNotEmpty
        assertThat(result.live).isEqualTo(result.backtest)
    }

    @Test
    fun `same symbol multi-timeframe warmup has full-state parity`() {
        val dsl =
            """
            STRATEGY multi_timeframe_warmup VERSION 1
            SYMBOLS
              fast = BACKTEST:X EVERY 1m
              slow = BACKTEST:X EVERY 5m
            RULES
              WHEN sma(fast.close, 2) > sma(slow.close, 3) AND POSITION.fast = 0
              THEN BUY fast SIZING 1
            """.trimIndent()
        val tape =
            (0..3).map { index ->
                Tick("BACKTEST:X", BigDecimal("150"), index * TimeWindow.ONE_MINUTE.durationMs)
            }
        val warmupByStream =
            mapOf(
                WarmupStream("BACKTEST:X", TimeWindow.ONE_MINUTE) to
                    warmupCandles(TimeWindow.ONE_MINUTE, 2, "200"),
                WarmupStream("BACKTEST:X", TimeWindow.FIVE_MINUTES) to
                    warmupCandles(TimeWindow.FIVE_MINUTES, 3, "100"),
            )

        val result =
            DslParityHarness.run(
                strategyId = "multi_timeframe_warmup",
                source = dsl,
                ticks = tape,
                warmupByStream = warmupByStream,
            )

        assertThat(result.live).isEqualTo(result.backtest)
        assertThat(result.backtest.trades).hasSize(1)
        assertThat(
            result.backtest.trades
                .single()
                .price,
        ).isEqualTo("150")
    }

    private fun warmupCandles(
        window: TimeWindow,
        count: Int,
        price: String,
    ): List<Candle> =
        (count downTo 1).map { offset ->
            val endTime = -window.durationMs * (offset - 1L)
            Candle(
                symbol = "BACKTEST:X",
                open = BigDecimal(price),
                high = BigDecimal(price),
                low = BigDecimal(price),
                close = BigDecimal(price),
                volume = BigDecimal.ONE,
                startTime = endTime - window.durationMs,
                endTime = endTime,
            )
        }
}
