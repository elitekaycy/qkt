package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MultiTimeframeEndToEndTest {
    private fun tick(
        symbol: String,
        price: String,
        ts: Long,
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = ts, volume = BigDecimal.ONE)

    @Test
    fun `btc 1m and btc_h1 1h fire on independent cadences`() {
        val ast =
            strategy("mtf", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val btc_h1 = stream("btc_h1", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")
                rule {
                    whenever(btc.close gt 110.bd)
                    then { buy(stream = btc, qty = BigDecimal("0.001").bd) }
                }
                rule {
                    whenever(btc_h1.close gt 110.bd)
                    then { buy(stream = btc_h1, qty = BigDecimal("2.0").bd) }
                }
            }
        val strategy = AstCompiler().compile(ast)

        // 90 minutes of ticks at 30s cadence, price always 120 (above threshold)
        val ticks: List<Tick> =
            (0L..(90L * 60_000L) step 30_000L).map { t -> tick("BTCUSDT", "120", t) }

        val result =
            Backtest(
                strategies = listOf("mtf" to strategy),
                ticks = ticks,
                initialTimestamp = 0L,
            ).run()

        // btc 1m: candle closes once per minute boundary crossed. 90 boundaries → 90 closes.
        // btc_h1: candle closes once per hour boundary. 1 boundary at t=3_600_000 → 1 close.
        val btc1mTrades = result.trades.filter { it.trade.quantity.compareTo(BigDecimal("0.001")) == 0 }
        val btc1hTrades = result.trades.filter { it.trade.quantity.compareTo(BigDecimal("2.0")) == 0 }

        assertThat(btc1mTrades).hasSize(90)
        assertThat(btc1hTrades).hasSize(1)
    }

    @Test
    fun `cross-timeframe condition btc with btc_h1 evaluates correctly`() {
        // Rule: WHEN btc.close > 105 AND btc_h1.close > 100 THEN buy(btc, qty=1)
        // After the first hourly close has happened, btc_h1.close is 120 (>100).
        // Each 1m close after t=3_600_000 evaluates btc.close (120, >105) AND btc_h1.close
        // (120, >100, hub.latest) → fires.
        val ast =
            strategy("crosstf", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val btc_h1 = stream("btc_h1", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")
                rule {
                    whenever((btc.close gt 105.bd) and (btc_h1.close gt 100.bd))
                    then { buy(stream = btc, qty = BigDecimal("0.5").bd) }
                }
            }
        val strategy = AstCompiler().compile(ast)

        // 90 minutes of ticks at 30s, price 120
        val ticks: List<Tick> =
            (0L..(90L * 60_000L) step 30_000L).map { t -> tick("BTCUSDT", "120", t) }

        val result =
            Backtest(
                strategies = listOf("crosstf" to strategy),
                ticks = ticks,
                initialTimestamp = 0L,
            ).run()

        // Before t=3_600_000: btc_h1 has no closed candle → cross-stream read Undefined → rule skipped.
        // After: 30 1m closes from t=3_660_000..5_400_000 (minutes 61..90), 30 fires.
        val trades = result.trades.filter { it.trade.quantity.compareTo(BigDecimal("0.5")) == 0 }
        assertThat(trades).hasSize(30)
    }
}
