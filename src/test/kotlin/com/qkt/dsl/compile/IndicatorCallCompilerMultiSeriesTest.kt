package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.indicators.MultiIndicator
import com.qkt.indicators.catalog.ConfirmRatio
import com.qkt.indicators.catalog.OlsResidual
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `resid(...)` and `confirm_ratio(...)` compile to k-series bindings whose value the compiled expression reads. */
class IndicatorCallCompilerMultiSeriesTest {
    private val streams = mapOf("b" to HubKey("BACKTEST", "BTCUSDT", "1m"))
    private val bars =
        listOf(
            "10" to "9",
            "12" to "10",
            "11" to "13",
            "15" to "12",
            "14" to "16",
            "18" to "15",
            "17" to "19",
        )

    private fun num(v: String) = NumLit(BigDecimal(v))

    private fun call(
        name: String,
        vararg args: ExprAst,
    ) = IndicatorCall(name, args.toList())

    private val close = StreamFieldRef("b", "close")
    private val open = StreamFieldRef("b", "open")

    private fun ctx(
        close: String,
        open: String,
    ): EvalContext {
        val c = BigDecimal(close)
        val o = BigDecimal(open)
        val candle = Candle("BACKTEST:BTCUSDT", o, c.max(o), c.min(o), c, BigDecimal.ONE, 0L, 59_999L)
        return EvalContext(candle, streams, emptyMap(), testStrategyContext())
    }

    /** Feeds every bar and pairs the compiled value after each bar with the reference indicator's. */
    private fun replay(
        expr: IndicatorCall,
        reference: MultiIndicator,
    ): List<Pair<Value, BigDecimal?>> {
        val bag = IndicatorBinding.Bag()
        val compiled = ExprCompiler(bindings = bag).compile(expr)
        return bars.map { (c, o) ->
            val ec = ctx(c, o)
            bag.updateAll(ec)
            reference.update(listOf(BigDecimal(c), BigDecimal(o)))
            compiled.evaluate(ec) to reference.value()?.takeIf { reference.isReady }
        }
    }

    private fun assertMatches(rows: List<Pair<Value, BigDecimal?>>) {
        assertThat(rows.first().first).isEqualTo(Value.Undefined)
        assertThat(rows.last().first).isInstanceOf(Value.Num::class.java)
        for ((compiled, expected) in rows) {
            if (expected == null) {
                assertThat(compiled).isEqualTo(Value.Undefined)
            } else {
                assertThat((compiled as Value.Num).v).isEqualByComparingTo(expected)
            }
        }
    }

    @Test
    fun `resid reads the OLS residual of the dependent series on the regressors`() {
        assertMatches(replay(call("resid", close, open, num("4")), OlsResidual(period = 4, regressorCount = 1)))
    }

    @Test
    fun `confirm_ratio reads the share of peers confirming the signal`() {
        assertMatches(replay(call("confirm_ratio", close, open, num("3")), ConfirmRatio(period = 3, peerCount = 1)))
    }

    @Test
    fun `resid rejects too few arguments and a dependent series with no stream`() {
        assertThatThrownBy { ExprCompiler().compile(call("resid", close, num("4"))) }
            .hasMessageContaining("resid needs a dependent series, at least one regressor, and a period")
        assertThatThrownBy { ExprCompiler().compile(call("resid", num("1"), close, num("4"))) }
            .hasMessageContaining("resid dependent series must reference a stream")
    }

    @Test
    fun `confirm_ratio rejects too few arguments, a fractional lookback and a signal with no stream`() {
        assertThatThrownBy { ExprCompiler().compile(call("confirm_ratio", close, num("4"))) }
            .hasMessageContaining("confirm_ratio needs a signal series, at least one peer, and a lookback")
        assertThatThrownBy { ExprCompiler().compile(call("confirm_ratio", close, open, num("2.5"))) }
            .hasMessageContaining("confirm_ratio lookback (last argument) must be an integer literal")
        assertThatThrownBy { ExprCompiler().compile(call("confirm_ratio", close, open, open)) }
            .hasMessageContaining("confirm_ratio lookback (last argument) must be an integer literal")
        assertThatThrownBy { ExprCompiler().compile(call("confirm_ratio", num("1"), close, num("4"))) }
            .hasMessageContaining("confirm_ratio signal series must reference a stream")
    }

    @Test
    fun `a two-series indicator whose first series reads no stream is rejected`() {
        assertThatThrownBy { ExprCompiler().compile(call("correlation", num("1"), close, num("5"))) }
            .hasMessageContaining("Indicator correlation first series must reference a stream")
    }
}
