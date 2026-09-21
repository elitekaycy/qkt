package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.parity.ResearcherMatrixParityTest.Case
import com.qkt.parity.ResearcherMatrixParityTest.Companion.A
import com.qkt.parity.ResearcherMatrixParityTest.Companion.B
import com.qkt.parity.ResearcherMatrixParityTest.Companion.tape
import com.qkt.parity.ResearcherMatrixParityTest.Companion.warmup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The researcher matrix on 1h, 4h and 1d bars, where everything rides on warmup: history has to
 * supply the indicator state, the first live bar must close on the real boundary, and a session
 * rarely starts on one. Kept apart from the intraday matrix so each suite stays readable.
 */
class HigherTimeframeParityTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `hourly, four-hour and daily streams trade identically live and in backtest`(case: Case) {
        val result =
            DslParityHarness.run(
                strategyId = case.id,
                source = case.dsl,
                ticks = tape(case.symbols, minutes = case.hours * 60, startMinute = case.startMinute),
                warmupByStream = case.warmups.associate { (symbol, window, bars) -> warmup(symbol, window, bars) },
            )

        assertThat(result.live).isEqualTo(result.backtest)
        assertThat(result.backtest.trades).`as`("the case must actually trade").hasSizeGreaterThanOrEqualTo(2)
    }

    companion object {
        @JvmStatic
        fun cases(): List<Case> =
            listOf(
                Case(
                    id = "four_hour_and_daily_with_minute_trigger",
                    hours = 26,
                    symbols = listOf(A, B),
                    warmups =
                        listOf(
                            Triple(A, TimeWindow.ONE_MINUTE, 5),
                            Triple(A, TimeWindow.parse("4h"), 12),
                            Triple(B, TimeWindow.ONE_DAY, 8),
                        ),
                    dsl =
                        """
                        STRATEGY four_hour_and_daily_with_minute_trigger VERSION 1
                        SYMBOLS
                          trigger = $A EVERY 1m WARMUP 5 BARS
                          swing   = $A EVERY 4h WARMUP 12 BARS
                          regime  = $B EVERY 1d WARMUP 8 BARS
                        RULES
                          WHEN swing.close > ema(swing.close, 5) AND regime.close > sma(regime.close, 6)
                           AND trigger.close > 0 AND POSITION.trigger = 0
                          THEN BUY trigger SIZING 1
                          WHEN POSITION.trigger > 0 AND swing.close < ema(swing.close, 5)
                          THEN CLOSE trigger
                        """.trimIndent(),
                ),
                Case(
                    id = "hourly_channel_started_mid_bar",
                    symbols = listOf(A),
                    warmups = listOf(Triple(A, TimeWindow.FIVE_MINUTES, 6), Triple(A, TimeWindow.ONE_HOUR, 24)),
                    startMinute = 37,
                    hours = 26,
                    dsl =
                        """
                        STRATEGY hourly_channel_started_mid_bar VERSION 1
                        SYMBOLS
                          fast = $A EVERY 5m WARMUP 6 BARS
                          hr = $A EVERY 1h WARMUP 24 BARS
                        RULES
                          WHEN fast.close > sma(hr.close, 12) AND atr(hr.candle, 6) > 0 AND POSITION.fast = 0
                          THEN BUY fast SIZING 1
                          WHEN POSITION.fast > 0 AND fast.close < sma(hr.close, 12)
                          THEN CLOSE fast
                        """.trimIndent(),
                ),
            )
    }
}
