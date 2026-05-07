package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionCompilerTest {
    private val candle =
        Candle(
            "BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            0L,
            1L,
        )
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `BUY emits Signal Buy with SizeQty`() {
        val action =
            Buy(
                stream = "btc",
                opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("2"))), orderType = Market),
            )
        val sig = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)
        assertThat(sig).isEqualTo(Signal.Buy("BTCUSDT", BigDecimal("2")))
    }

    @Test
    fun `SELL emits Signal Sell`() {
        val action =
            Sell(stream = "btc", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("3")))))
        val sig = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)
        assertThat(sig).isEqualTo(Signal.Sell("BTCUSDT", BigDecimal("3")))
    }

    @Test
    fun `Buy without sizing is rejected`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(Buy(stream = "btc", opts = ActionOpts()))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `non-quantity sizing is rejected in 11b`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(
                Buy(
                    stream = "btc",
                    opts = ActionOpts(sizing = SizeRiskFrac(NumLit(BigDecimal("0.01")))),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-market order type is rejected in 11b`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(
                Buy(
                    stream = "btc",
                    opts =
                        ActionOpts(
                            sizing = SizeQty(NumLit(BigDecimal.ONE)),
                            orderType = Limit(NumLit(BigDecimal("100"))),
                        ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Close action is unsupported in 11b`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(Close("btc"))
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
