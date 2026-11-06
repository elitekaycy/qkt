package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ExitField
import com.qkt.dsl.ast.ExitRef
import com.qkt.execution.ExitReason
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `EXIT.*` reads inside an exit hook evaluate against the closing fill. */
class ExitRefEvaluationTest {
    private val one = BigDecimal.ONE
    private val exitCompiler = ExprCompiler().forExitHooks()

    private fun ctx(exit: ExitContext?) =
        EvalContext(
            candle = Candle("BACKTEST:BTCUSDT", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
            exitContext = exit,
        )

    private val takeProfit = ExitContext(BigDecimal("105.5"), Side.SELL, BigDecimal("2"), BigDecimal("11"), ExitReason.TAKE_PROFIT)

    private fun read(
        field: ExitField,
        exit: ExitContext? = takeProfit,
    ): Value = exitCompiler.compile(ExitRef(field)).evaluate(ctx(exit))

    @Test
    fun `price and pnl read the closing fill`() {
        assertThat((read(ExitField.PRICE) as Value.Num).v).isEqualByComparingTo("105.5")
        assertThat((read(ExitField.PNL) as Value.Num).v).isEqualByComparingTo("11")
    }

    @Test
    fun `a take-profit exit reports its reason as TP and others by name`() {
        assertThat(read(ExitField.REASON)).isEqualTo(Value.Str("TP"))
        assertThat(read(ExitField.REASON, takeProfit.copy(reason = ExitReason.STOP))).isEqualTo(Value.Str("STOP"))
    }

    @Test
    fun `an exit read evaluated outside a hook fails`() {
        assertThatThrownBy { read(ExitField.PRICE, exit = null) }
            .hasMessageContaining("EXIT.price is only valid in an exit hook")
    }

    @Test
    fun `an exit-hook compiler is reused rather than rebuilt`() {
        assertThat(exitCompiler.forExitHooks()).isSameAs(exitCompiler)
    }
}
