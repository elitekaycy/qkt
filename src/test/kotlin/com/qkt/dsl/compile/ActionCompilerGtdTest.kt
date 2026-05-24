package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionCompilerGtdTest {
    private fun makeCtx(): EvalContext {
        val close = BigDecimal("1.1000")
        val candle =
            Candle(
                "BACKTEST:EURUSD",
                close,
                close,
                close,
                close,
                BigDecimal.ZERO,
                0L,
                1L,
            )
        return EvalContext(
            candle = candle,
            streams = mapOf("eur" to HubKey("BACKTEST", "EURUSD", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    }

    private fun compileBuy(opts: ActionOpts): (EvalContext) -> List<Signal> =
        ActionCompiler(ExprCompiler()).compile(Buy(stream = "eur", opts = opts))

    @Test
    fun `TIF GTD with literal deadline stamps expiresAt on the request`() {
        val opts =
            ActionOpts(
                sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                orderType = Limit(NumLit(BigDecimal("1.0500"))),
                tif = Gtd(NumLit(BigDecimal("1700001800000"))),
            )
        val sigs = compileBuy(opts).invoke(makeCtx())
        val req = (sigs.single() as Signal.Submit).request as OrderRequest.Limit
        assertThat(req.timeInForce).isEqualTo(TimeInForce.GTD)
        assertThat(req.expiresAt).isEqualTo(1700001800000L)
    }

    @Test
    fun `TIF GTD with NOW expression stamps expiresAt computed per emit`() {
        val opts =
            ActionOpts(
                sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                orderType = Limit(NumLit(BigDecimal("1.0500"))),
                tif =
                    Gtd(
                        BinaryOp(
                            op = BinOp.ADD,
                            lhs = NowAccessor(NowField.EPOCH_MS),
                            rhs = NumLit(BigDecimal("3600000")),
                        ),
                    ),
            )
        val sigs = compileBuy(opts).invoke(makeCtx())
        val req = (sigs.single() as Signal.Submit).request as OrderRequest.Limit
        assertThat(req.timeInForce).isEqualTo(TimeInForce.GTD)
        // testStrategyContext() uses FixedClock(0L); NOW + 3_600_000 = 3_600_000.
        assertThat(req.expiresAt).isEqualTo(3_600_000L)
    }

    @Test
    fun `TIF GTD on a MARKET action fails compile with the pointed message`() {
        val opts =
            ActionOpts(
                sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                // orderType defaulted to Market
                tif = Gtd(NumLit(BigDecimal("1700000000000"))),
            )
        assertThatThrownBy { compileBuy(opts) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("TIF GTD is only valid on pending order types")
            .hasMessageContaining("MARKET")
    }
}
