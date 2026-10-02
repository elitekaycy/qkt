package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A rule that acts on no stream runs on the closes of the streams it reads, not only on the first
 * declared one: with gold (declared first) closed for the weekend, a rule reading btc and gold still
 * fires on btc's closes.
 */
class StreamlessRuleTriggerTest {
    private fun bar(
        symbol: String,
        close: String,
        start: Long,
    ) = Candle(
        symbol,
        Money.of(close),
        Money.of(close),
        Money.of(close),
        Money.of(close),
        Money.of("1"),
        start,
        start + 60_000L,
    )

    @Test
    fun `a CLOSE_ALL reading two streams fires on the open stream while the first is closed`() {
        val source = InMemoryMarketSource()
        source.seedBars("BACKTEST:XAUUSD", TimeWindow.ONE_MINUTE, listOf(bar("BACKTEST:XAUUSD", "2000", 0L)))
        source.seedBars(
            "BACKTEST:BTCUSD",
            TimeWindow.ONE_MINUTE,
            (0 until 5).map { bar("BACKTEST:BTCUSD", if (it < 3) "100" else "200", it * 60_000L) },
        )
        val strategy =
            compile(
                """
                STRATEGY weekend_kill VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                  btc = BACKTEST:BTCUSD EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc
                  WHEN btc.close > 150 AND gold.close > 0
                  THEN CLOSE_ALL
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("weekend_kill" to strategy),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:XAUUSD", "BACKTEST:BTCUSD"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(5 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades.map { it.trade.side }).containsExactly(Side.BUY, Side.SELL)
    }
}
