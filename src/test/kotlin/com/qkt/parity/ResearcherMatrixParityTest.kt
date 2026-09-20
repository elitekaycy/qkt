package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.WarmupStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Strategies the way a researcher writes them: several symbols, each on its own timeframe with
 * its own warmup depth, indicators of different families combined in one condition. Every entry
 * and exit is gated on indicator VALUES across those streams, so a live value that differs from
 * the backtest's — a bar closed at a different boundary, a warmup applied to the wrong stream, a
 * higher timeframe fed late — shows up as a different trade list.
 */
class ResearcherMatrixParityTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `mixed symbols, timeframes and warmups trade identically live and in backtest`(case: Case) {
        val result =
            DslParityHarness.run(
                strategyId = case.id,
                source = case.dsl,
                ticks = tape(case.symbols, minutes = 6 * 60),
                warmupByStream = case.warmups.associate { (symbol, window, bars) -> warmup(symbol, window, bars) },
            )

        assertThat(result.live).isEqualTo(result.backtest)
        assertThat(result.backtest.trades).`as`("the case must actually trade").hasSizeGreaterThanOrEqualTo(2)
    }

    data class Case(
        val id: String,
        val symbols: List<String>,
        val warmups: List<Triple<String, TimeWindow, Int>>,
        val dsl: String,
    ) {
        override fun toString() = id
    }

    companion object {
        private const val A = "BACKTEST:AAA"
        private const val B = "BACKTEST:BBB"

        @JvmStatic
        fun cases(): List<Case> =
            listOf(
                Case(
                    id = "two_symbols_1m_vs_15m",
                    symbols = listOf(A, B),
                    warmups = listOf(Triple(A, TimeWindow.ONE_MINUTE, 6), Triple(B, TimeWindow.FIFTEEN_MINUTES, 20)),
                    dsl =
                        """
                        STRATEGY two_symbols_1m_vs_15m VERSION 1
                        SYMBOLS
                          a = $A EVERY 1m WARMUP 6 BARS
                          b = $B EVERY 15m WARMUP 20 BARS
                        RULES
                          WHEN rsi(a.close, 5) > 55 AND ema(b.close, 3) > sma(b.close, 8) AND POSITION.a = 0
                          THEN BUY a SIZING 1
                          WHEN POSITION.a > 0 AND (rsi(a.close, 5) < 45 OR ema(b.close, 3) < sma(b.close, 8))
                          THEN CLOSE a
                        """.trimIndent(),
                ),
                Case(
                    id = "one_symbol_1m_5m_1h",
                    symbols = listOf(A),
                    warmups =
                        listOf(
                            Triple(A, TimeWindow.ONE_MINUTE, 10),
                            Triple(A, TimeWindow.FIVE_MINUTES, 12),
                            Triple(A, TimeWindow.ONE_HOUR, 4),
                        ),
                    dsl =
                        """
                        STRATEGY one_symbol_1m_5m_1h VERSION 1
                        SYMBOLS
                          fast = $A EVERY 1m WARMUP 10 BARS
                          mid  = $A EVERY 5m WARMUP 12 BARS
                          slow = $A EVERY 1h WARMUP 4 BARS
                        RULES
                          WHEN fast.close > bollinger_middle(mid.close, 6, 2) AND mid.close > sma(slow.close, 3)
                           AND POSITION.fast = 0
                          THEN BUY fast SIZING 1 BRACKET { STOP LOSS BY 4, TAKE PROFIT BY 6 }
                          WHEN POSITION.fast > 0 AND fast.close < ema(mid.close, 4)
                          THEN CLOSE fast
                        """.trimIndent(),
                ),
                Case(
                    id = "cross_symbol_zscore_and_atr",
                    symbols = listOf(A, B),
                    warmups =
                        listOf(
                            Triple(A, TimeWindow.FIVE_MINUTES, 15),
                            Triple(B, TimeWindow.FIVE_MINUTES, 4),
                            Triple(B, TimeWindow.ONE_HOUR, 5),
                        ),
                    dsl =
                        """
                        STRATEGY cross_symbol_zscore_and_atr VERSION 1
                        SYMBOLS
                          a  = $A EVERY 5m WARMUP 15 BARS
                          b  = $B EVERY 5m WARMUP 4 BARS
                          bh = $B EVERY 1h WARMUP 5 BARS
                        RULES
                          WHEN zscore(a.close, 10) < -0.5 AND atr(b.candle, 3) > 0 AND b.close > lowest(bh.close, 4)
                           AND POSITION.b = 0
                          THEN BUY b SIZING 1
                          WHEN POSITION.b > 0 AND (zscore(a.close, 10) > 0.5 OR b.close < lowest(bh.close, 4))
                          THEN CLOSE b
                        """.trimIndent(),
                ),
                Case(
                    id = "shallow_warmup_beside_deep_warmup",
                    symbols = listOf(A, B),
                    warmups = listOf(Triple(A, TimeWindow.ONE_MINUTE, 2), Triple(B, TimeWindow.FIFTEEN_MINUTES, 30)),
                    dsl =
                        """
                        STRATEGY shallow_warmup_beside_deep_warmup VERSION 1
                        SYMBOLS
                          a = $A EVERY 1m WARMUP 2 BARS
                          b = $B EVERY 15m WARMUP 30 BARS
                        RULES
                          WHEN macd_hist(b.close, 4, 9, 3) > 0 AND a.close > sma(a.close, 2) AND POSITION.a = 0
                          THEN BUY a SIZING 1
                          WHEN POSITION.a > 0 AND (macd_hist(b.close, 4, 9, 3) < 0 OR a.close < sma(a.close, 2))
                          THEN CLOSE a
                        """.trimIndent(),
                ),
            )

        /** Six hours of one tick a minute per symbol: a slow swing with a faster wobble, repeatable. */
        private fun tape(
            symbols: List<String>,
            minutes: Int,
        ): List<Tick> =
            (0 until minutes).flatMap { minute ->
                symbols.mapIndexed { index, symbol ->
                    Tick(symbol, price(minute, index), minute * 60_000L + index)
                }
            }

        private fun price(
            minute: Int,
            phase: Int,
        ): BigDecimal {
            val swing = 12.0 * Math.sin((minute + 40 * phase) / 47.0)
            val wobble = 3.0 * Math.sin((minute + 7 * phase) / 5.0)
            return BigDecimal(100.0 + 10 * phase + swing + wobble).setScale(2, java.math.RoundingMode.HALF_UP)
        }

        private fun warmup(
            symbol: String,
            window: TimeWindow,
            bars: Int,
        ): Pair<WarmupStream, List<Candle>> =
            WarmupStream(symbol, window) to
                (bars downTo 1).map { offset ->
                    val endTime = -window.durationMs * (offset - 1L)
                    val close = price(-offset * (window.durationMs / 60_000L).toInt(), if (symbol == A) 0 else 1)
                    Candle(symbol, close, close, close, close, BigDecimal.ONE, endTime - window.durationMs, endTime)
                }
    }
}
