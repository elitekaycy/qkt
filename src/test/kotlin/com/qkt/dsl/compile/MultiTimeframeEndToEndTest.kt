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

    /** 120 on even minutes, 100 on odd — each even-minute close is a fresh rising edge. */
    private fun alternatingPrice(t: Long): String = if ((t / 60_000L) % 2 == 0L) "120" else "100"

    @Test
    fun `btc 1m and btcH1 1h fire on independent cadences`() {
        val ast =
            strategy("mtf", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val btcH1 = stream("btcH1", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")
                rule {
                    whenever(btc.close gt 110.bd)
                    then { buy(stream = btc, qty = BigDecimal("0.001").bd) }
                }
                rule {
                    whenever(btcH1.close gt 90.bd)
                    then { buy(stream = btcH1, qty = BigDecimal("2.0").bd) }
                }
            }
        val strategy = AstCompiler().compile(ast)

        // 90 minutes of ticks at 30s cadence, alternating 120/100 per minute so the 1m
        // rule sees a rising edge on every even-minute close (actions are edge-gated).
        val ticks: List<Tick> =
            (0L..(90L * 60_000L) step 30_000L).map { t -> tick("BACKTEST:BTCUSDT", alternatingPrice(t), t) }

        val result =
            Backtest(
                strategies = listOf("mtf" to strategy),
                ticks = ticks,
                initialTimestamp = 0L,
            ).run()

        // btc 1m: minutes 0..89 close with 120,100,120,... — 45 rising edges → 45 fires.
        // btcH1: one hourly close at t=3_600_000 (close 100 > 90) → 1 fire.
        val btc1mTrades = result.trades.filter { it.trade.quantity.compareTo(BigDecimal("0.001")) == 0 }
        val btc1hTrades = result.trades.filter { it.trade.quantity.compareTo(BigDecimal("2.0")) == 0 }

        assertThat(btc1mTrades).hasSize(45)
        assertThat(btc1hTrades).hasSize(1)
    }

    @Test
    fun `cross-timeframe condition btc with btcH1 evaluates correctly`() {
        // Rule: WHEN btc.close > 105 AND btcH1.close > 90 THEN buy(btc, qty=0.5)
        // Before the first hourly close, the btcH1 read is Undefined → rule skipped.
        // After it, every even-minute 1m close is a rising edge of the AND.
        val ast =
            strategy("crosstf", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val btcH1 = stream("btcH1", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")
                rule {
                    whenever((btc.close gt 105.bd) and (btcH1.close gt 90.bd))
                    then { buy(stream = btc, qty = BigDecimal("0.5").bd) }
                }
            }
        val strategy = AstCompiler().compile(ast)

        // 90 minutes of ticks at 30s, alternating 120/100 per minute
        val ticks: List<Tick> =
            (0L..(90L * 60_000L) step 30_000L).map { t -> tick("BACKTEST:BTCUSDT", alternatingPrice(t), t) }

        val result =
            Backtest(
                strategies = listOf("crosstf" to strategy),
                ticks = ticks,
                initialTimestamp = 0L,
            ).run()

        // The hourly candle closes at t=3_600_000. From there, 1m closes for minutes
        // 60..89 evaluate the full condition; the even minutes (60, 62, ..., 88) close
        // at 120 and are rising edges → 15 fires.
        val trades = result.trades.filter { it.trade.quantity.compareTo(BigDecimal("0.5")) == 0 }
        assertThat(trades).hasSize(15)
    }
}
