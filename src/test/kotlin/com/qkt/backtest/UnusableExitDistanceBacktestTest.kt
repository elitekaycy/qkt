package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.candle
import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A stop or target distance that is not positive would place the level on the wrong side of entry
 * (a BUY whose stop sits above its fill is stopped out at once). As a literal it is a compile error;
 * computed when the rule fires, it skips the order and the run goes on.
 */
class UnusableExitDistanceBacktestTest {
    private fun strategy(order: String) =
        compile(
            """
            STRATEGY unusable_exit VERSION 1
            SYMBOLS
              btc = BYBIT_SPOT:BTCUSDT EVERY 1m
            RULES
              WHEN btc.close > 0 AND POSITION.btc = 0
              THEN BUY btc SIZING 0.1 $order
            """.trimIndent(),
        )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "BRACKET { STOP_LOSS BY btc.close - 500, TAKE_PROFIT BY 6 }",
            "BRACKET { STOP_LOSS BY 3, TAKE_PROFIT BY btc.close - 100 }",
            "BRACKET { STOP_LOSS PCT btc.close - 500, TAKE_PROFIT BY 6 }",
            "ORDER_TYPE = TRAILING PCT btc.close - 500",
            "ORDER_TYPE = TRAILING BY btc.close - 500",
        ],
    )
    fun `a computed distance that is not positive skips the order and the run completes`(order: String) {
        val result = run(order)

        assertThat(result.trades).isEmpty()
    }

    private fun run(order: String): BacktestResult {
        val source = InMemoryMarketSource()
        source.seedBars(
            "BYBIT_SPOT:BTCUSDT",
            TimeWindow.ONE_MINUTE,
            (0 until 3).map { candle("100", "101", "99", "100", it * 60_000L) },
        )
        return Backtest
            .fromSource(
                strategies = listOf("unusable_exit" to strategy(order)),
                source = source,
                request =
                    MarketRequest(
                        symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                        from = Instant.ofEpochMilli(0L),
                        to = Instant.ofEpochMilli(3 * 60_000L),
                    ),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "BRACKET { STOP_LOSS BY -3, TAKE_PROFIT BY 6 }",
            "BRACKET { STOP_LOSS BY 3, TAKE_PROFIT BY 0 }",
            "ORDER_TYPE = TRAILING BY -2",
            "ORDER_TYPE = TRAILING PCT 0",
        ],
    )
    fun `a literal distance that is not positive is a compile error`(order: String) {
        assertThatThrownBy { strategy(order) }.hasMessageContaining("must be greater than 0")
    }
}
