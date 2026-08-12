package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.candleToTicks
import com.qkt.parity.DslParityHarness
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class HigherTimeframeWarmupParityTest {
    private data class Case(
        val id: String,
        val spec: String,
        val bars: Int,
    )

    @TestFactory
    fun `higher timeframe explicit warmup bars match live and backtest parity`(): List<DynamicTest> =
        CASES.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val window = TimeWindow.parse(case.spec)
                val source = strategy(case)
                val stream = WarmupStream(SYMBOL, window)
                val compiled =
                    AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as PerStreamWarmable

                val spec = compiled.perStreamWarmup.getValue(stream) as WarmupSpec.Bars
                assertThat(spec.window).isEqualTo(window)
                assertThat(spec.count).isEqualTo(case.bars)

                val warmup = warmupBars(window, case.bars)
                val warmupTicks = warmup.flatMap(::candleToTicks)
                assertThat(warmupTicks).hasSize(case.bars * 4)
                assertThat(warmupTicks).allSatisfy { tick ->
                    assertThat(tick.timestamp).isLessThan(0L)
                }

                val result =
                    DslParityHarness.run(
                        strategyId = case.id,
                        source = source,
                        ticks = liveTicks(window),
                        warmupByStream = mapOf(stream to warmup),
                        candleWindow = window,
                    )

                assertThat(result.live).isEqualTo(result.backtest)
                val trade = result.backtest.trades.single()
                assertThat(trade.side).isEqualTo("BUY")
                assertThat(trade.price).isEqualTo("100")
                val position = result.backtest.positions.single()
                assertThat(position.symbol).isEqualTo(SYMBOL)
                assertThat(position.quantity).isEqualTo("1")
                assertThat(result.backtest.rejections).isEmpty()
                assertThat(result.backtest.halts).isEmpty()
            }
        }

    private fun strategy(case: Case): String =
        """
        STRATEGY ${case.id} VERSION 1
        SYMBOLS x = $SYMBOL EVERY ${case.spec} WARMUP ${case.bars} BARS
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 1
        """.trimIndent()

    private fun liveTicks(window: TimeWindow): List<Tick> =
        listOf(
            Tick(SYMBOL, BigDecimal("100"), 0L),
            Tick(SYMBOL, BigDecimal("100"), window.durationMs),
        )

    private fun warmupBars(
        window: TimeWindow,
        count: Int,
    ): List<Candle> =
        (0 until count).map { index ->
            val start = (index - count).toLong() * window.durationMs
            Candle(
                symbol = SYMBOL,
                open = BigDecimal("99"),
                high = BigDecimal("101"),
                low = BigDecimal("98"),
                close = BigDecimal("100"),
                volume = BigDecimal(index + 1),
                startTime = start,
                endTime = start + window.durationMs,
            )
        }

    private companion object {
        const val SYMBOL = "BACKTEST:X"
        val CASES =
            listOf(
                Case("warmup_15m_one_hour", "15m", 4),
                Case("warmup_15m_one_day", "15m", 96),
                Case("warmup_15m_two_days", "15m", 192),
                Case("warmup_1h_one_hour", "1h", 1),
                Case("warmup_1h_one_day", "1h", 24),
                Case("warmup_1h_two_days", "1h", 48),
                Case("warmup_4h_four_hours", "4h", 1),
                Case("warmup_4h_one_day", "4h", 6),
                Case("warmup_4h_two_days", "4h", 12),
            )
    }
}
