package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderTypeCompilerTest {
    private fun ec(closePrice: String) =
        EvalContext(
            candle =
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal(closePrice),
                    BigDecimal(closePrice),
                    BigDecimal(closePrice),
                    BigDecimal(closePrice),
                    BigDecimal.ZERO,
                    0L,
                    60_000L,
                ),
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private fun compiler() = OrderTypeCompiler(ExprCompiler())

    @Test
    fun `Market entry price is candle close`() {
        val c = compiler().compile(Market)
        assertThat(c.entryPrice.evaluate(ec("100"))).isEqualByComparingTo("100")
    }

    @Test
    fun `Limit builds Limit OrderRequest with absolute price`() {
        val c = compiler().compile(Limit(NumLit(BigDecimal("99.5"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-1",
                side = Side.BUY,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.Limit
        assertThat(req.limitPrice).isEqualByComparingTo("99.5")
        assertThat(req.symbol).isEqualTo("BACKTEST:BTCUSDT")
        assertThat(req.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `Limit entryPrice equals the limit price`() {
        val c = compiler().compile(Limit(NumLit(BigDecimal("99.5"))))
        assertThat(c.entryPrice.evaluate(ec("100"))).isEqualByComparingTo("99.5")
    }

    @Test
    fun `Stop builds Stop OrderRequest`() {
        val c = compiler().compile(Stop(NumLit(BigDecimal("95"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-2",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.Stop
        assertThat(req.stopPrice).isEqualByComparingTo("95")
    }

    @Test
    fun `StopLimit builds StopLimit OrderRequest`() {
        val c = compiler().compile(StopLimit(NumLit(BigDecimal("95")), NumLit(BigDecimal("94"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-3",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.StopLimit
        assertThat(req.stopPrice).isEqualByComparingTo("95")
        assertThat(req.limitPrice).isEqualByComparingTo("94")
    }

    @Test
    fun `TrailingBy builds TrailingStop with ABSOLUTE mode`() {
        val c = compiler().compile(TrailingBy(NumLit(BigDecimal("3"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-4",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.TrailingStop
        assertThat(req.trailMode).isEqualTo(TrailMode.ABSOLUTE)
        assertThat(req.trailAmount).isEqualByComparingTo("3")
    }

    @Test
    fun `TrailingPct builds TrailingStop with PERCENT mode and 0-100 scale`() {
        val c = compiler().compile(TrailingPct(NumLit(BigDecimal("0.05"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-5",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.TrailingStop
        assertThat(req.trailMode).isEqualTo(TrailMode.PERCENT)
        assertThat(req.trailAmount).isEqualByComparingTo("5")
    }
}
