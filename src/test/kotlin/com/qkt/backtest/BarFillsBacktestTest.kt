package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.candle
import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Side
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A symbol the store has only bars for replays ticks synthesized from them even without `--bars`;
 * its triggered exits fill at their own level, as live fills a level the market trades through,
 * never at the synthetic bar extreme.
 */
class BarFillsBacktestTest {
    private fun backtest(
        candles: List<Candle>,
        bracket: String,
        brokerKind: BrokerKind = BrokerKind.PAPER,
    ): Backtest {
        val source = InMemoryMarketSource()
        source.seedBars("BYBIT_SPOT:BTCUSDT", TimeWindow.ONE_MINUTE, candles)
        val strategy =
            compile(
                """
                STRATEGY bar_fills VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BYBIT_SPOT:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0 AND POSITION.btc = 0
                  THEN BUY btc BRACKET { $bracket }
                """.trimIndent(),
            )
        return Backtest.fromSource(
            strategies = listOf("bar_fills" to strategy),
            source = source,
            request =
                MarketRequest(
                    symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                    from = Instant.ofEpochMilli(0L),
                    to = Instant.ofEpochMilli(candles.last().endTime),
                ),
            candleWindow = TimeWindow.ONE_MINUTE,
            brokerKind = brokerKind,
            executionConfig = ExecutionSimulationConfig.forBrokerKind(brokerKind),
        )
    }

    private fun exitPrice(backtest: Backtest) =
        backtest
            .run()
            .trades
            .map { it.trade }
            .first { it.side == Side.SELL }
            .price

    @Test
    fun `a stop the bar trades through fills at its level, not the bar low`() {
        val bars = listOf(candle("100", "110", "99", "105", 0L), candle("105", "106", "80", "100", 60_000L))

        val run = backtest(bars, "STOP LOSS AT 95, TAKE PROFIT AT 200")

        assertThat(run.barFills.at("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(exitPrice(run)).isEqualByComparingTo("95")
    }

    @Test
    fun `a take-profit the bar trades through fills at its level, not the bar high`() {
        val bars = listOf(candle("100", "110", "99", "105", 0L), candle("105", "120", "104", "110", 60_000L))

        assertThat(exitPrice(backtest(bars, "STOP LOSS AT 50, TAKE PROFIT AT 108"))).isEqualByComparingTo("108")
    }

    @Test
    fun `a stop the market gaps through fills at the gap's opening print`() {
        val bars =
            listOf(
                candle("100", "110", "99", "105", 0L),
                candle("105", "107", "100", "101", 60_000L),
                candle("90", "92", "85", "88", 180_000L),
            )

        assertThat(exitPrice(backtest(bars, "STOP LOSS AT 95, TAKE PROFIT AT 200"))).isEqualByComparingTo("90")
    }

    @Test
    fun `a stop the next contiguous bar opens beyond fills at that open`() {
        val bars =
            listOf(
                candle("100", "110", "99", "105", 0L),
                candle("105", "107", "100", "101", 60_000L),
                candle("90", "92", "85", "88", 120_000L),
            )

        assertThat(exitPrice(backtest(bars, "STOP LOSS AT 95, TAKE PROFIT AT 200"))).isEqualByComparingTo("90")
    }

    @Test
    fun `mt5-sim refuses a symbol it would replay from bars`() {
        val bars = listOf(candle("100", "110", "99", "105", 0L), candle("105", "106", "80", "100", 60_000L))

        assertThatThrownBy { backtest(bars, "STOP LOSS AT 95, TAKE PROFIT AT 200", BrokerKind.MT5_SIM) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--broker mt5-sim cannot replay [BYBIT_SPOT:BTCUSDT] from bars")
    }
}
