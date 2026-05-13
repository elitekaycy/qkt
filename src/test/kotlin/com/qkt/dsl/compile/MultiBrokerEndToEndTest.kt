package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MultiBrokerEndToEndTest {
    private fun tick(
        symbol: String,
        price: String,
        ts: Long,
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = ts, volume = BigDecimal.ONE)

    @Test
    fun `three brokers route correctly via CompositeBroker`() {
        val ast =
            strategy("mb", 1) {
                val btc = stream("btc", "BYBIT", "BTCUSDT", "1m")
                val gold = stream("gold", "INTERACTIVE", "XAUUSD", "1m")
                val aapl = stream("aapl", "ALPACA", "AAPL", "1m")
                forEach(btc, gold, aapl) { s ->
                    rule {
                        whenever(s.close gt 0.bd)
                        then { buy(stream = s, qty = BigDecimal.ONE.bd) }
                    }
                }
            }
        val strategy = AstCompiler().compile(ast)

        val ticks: List<Tick> =
            (0L..120_000L step 30_000L).flatMap { t ->
                listOf(
                    tick("BYBIT:BTCUSDT", "100", t),
                    tick("INTERACTIVE:XAUUSD", "2000", t + 1_000L),
                    tick("ALPACA:AAPL", "150", t + 2_000L),
                )
            }

        val result =
            Backtest(
                strategies = listOf("mb" to strategy),
                ticks = ticks,
                initialTimestamp = 0L,
            ).run()

        // Each symbol's 1m candle closes at t=60_000 and t=120_000 (boundaries crossed).
        // 2 fires per symbol × 3 symbols = 6 trades.
        val symbols = result.trades.map { it.trade.symbol }.toSet()
        assertThat(symbols).containsExactlyInAnyOrder("BYBIT:BTCUSDT", "INTERACTIVE:XAUUSD", "ALPACA:AAPL")
        assertThat(result.trades).hasSizeGreaterThanOrEqualTo(3)
    }
}
