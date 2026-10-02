package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.candle
import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A SIZING that is undefined, zero or negative skips its order; it never stops the run (live, the
 * same compiled strategy would stop trading).
 */
class UnusableSizingBacktestTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "SIZING 0.1 + ACCOUNT.last_trade_pnl",
            "SIZING RISK $ (50 + ACCOUNT.last_trade_pnl) BRACKET { STOP_LOSS BY 3, TAKE_PROFIT BY 6 }",
            "SIZING btc.close - 500",
            "SIZING 0",
        ],
    )
    fun `an unusable size skips the order and the run completes`(order: String) {
        val source = InMemoryMarketSource()
        source.seedBars(
            "BYBIT_SPOT:BTCUSDT",
            TimeWindow.ONE_MINUTE,
            (0 until 3).map { candle("100", "101", "99", "100", it * 60_000L) },
        )
        val strategy =
            compile(
                """
                STRATEGY unusable_size VERSION 1
                SYMBOLS
                  btc = BYBIT_SPOT:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0 AND POSITION.btc = 0
                  THEN BUY btc $order
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("unusable_size" to strategy),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(3 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades).isEmpty()
    }
}
