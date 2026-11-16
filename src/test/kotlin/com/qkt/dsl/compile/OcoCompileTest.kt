package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * `BUY`/`SELL ... OCO { STOP AT s, LIMIT AT t }` is the entry plus its two exits (actions.md "exit pair by
 * price"): it compiles to the same [OrderRequest.Bracket] as `BRACKET { STOP_LOSS AT s, TAKE_PROFIT AT t }`, so
 * the entry is placed and, on its fill, the stop and the limit close that position with one cancelling the other.
 * It never compiles to a standalone pair of opening orders (#1715).
 */
class OcoCompileTest {
    private val candle =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal.ZERO,
            0L,
            1L,
        )
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    private fun oco(
        stop: String,
        limit: String,
    ) = OcoAst(stop = ChildAt(NumLit(BigDecimal(stop))), limit = ChildAt(NumLit(BigDecimal(limit))))

    private fun opts(oco: OcoAst) = ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)), oco = oco)

    private fun bracketOf(sigs: List<Signal>): OrderRequest.Bracket {
        assertThat(sigs).hasSize(1)
        return (sigs.single() as Signal.Submit).request as OrderRequest.Bracket
    }

    @Test
    fun `BUY with an OCO exit pair is a market entry protected by a fixed stop and a target`() {
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(Buy("btc", opts(oco("90", "110")))).invoke(ctx)
        val req = bracketOf(sigs)
        assertThat(req.side).isEqualTo(Side.BUY)
        assertThat(req.quantity).isEqualByComparingTo("1")
        assertThat(req.entry).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(req.entry.side).isEqualTo(Side.BUY)
        assertThat(req.stopLoss).isEqualTo(StopLossSpec.Fixed(BigDecimal("90")))
        assertThat(req.takeProfit).isEqualByComparingTo("110")
        assertThat(req.stopLossAst).isEqualTo(ChildAt(NumLit(BigDecimal("90"))))
        assertThat(req.takeProfitAst).isEqualTo(ChildAt(NumLit(BigDecimal("110"))))
    }

    @Test
    fun `SELL with an OCO exit pair is a short entry whose stop sits above and target below`() {
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(Sell("btc", opts(oco("101.50", "98.50")))).invoke(ctx)
        val req = bracketOf(sigs)
        assertThat(req.side).isEqualTo(Side.SELL)
        assertThat(req.entry.side).isEqualTo(Side.SELL)
        assertThat(req.stopLoss).isEqualTo(StopLossSpec.Fixed(BigDecimal("101.50")))
        assertThat(req.takeProfit).isEqualByComparingTo("98.50")
    }

    @Test
    fun `an OCO whose stop sits on the entry price is refused, not sent unprotected`() {
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(Buy("btc", opts(oco("100", "110")))).invoke(ctx)
        assertThat(sigs).hasSize(1)
        val suppressed = sigs.single() as Signal.Suppressed
        assertThat(suppressed.reason).contains("stop distance is 0")
    }
}
