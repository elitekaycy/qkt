package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.indicators.MultiIndicator
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.SMA
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [IndicatorBinding.Bag] binding paths: indicator-fed, stream-fed, k-series and the argument checks. */
class IndicatorBindingBagTest {
    private val streams = mapOf("b" to HubKey("BACKTEST", "BTCUSDT", "1m"))

    private fun num(v: Int) = NumLit(BigDecimal(v))

    private fun call(
        name: String,
        vararg args: ExprAst,
    ) = IndicatorCall(name, args.toList())

    private fun field(name: String) = StreamFieldRef("b", name)

    private fun ctx(
        open: String,
        close: String,
    ): EvalContext {
        val o = BigDecimal(open)
        val c = BigDecimal(close)
        val candle = Candle("BACKTEST:BTCUSDT", o, c, o, c, BigDecimal.ONE, 0L, 59_999L)
        return EvalContext(candle, streams, emptyMap(), testStrategyContext())
    }

    @Test
    fun `an indicator call nested as the series feeds the outer indicator from the inner one's output`() {
        val bag = IndicatorBinding.Bag()
        val outer = bag.bind(call("EMA", call("SMA", field("close"), num(2)), num(2)))
        val sma = SMA(2)
        val ema = EMA(2)

        for (close in listOf("1", "2", "4", "7", "11")) {
            bag.updateForAlias("b", ctx(close, close))
            sma.update(BigDecimal(close))
            if (sma.isReady) ema.update(sma.value()!!)
        }

        assertThat(outer.rootAlias).isEqualTo("b")
        assertThat(outer.indicator.isReady).isTrue
        assertThat(outer.indicator.value()).isEqualByComparingTo(ema.value())
    }

    @Test
    fun `a stream-fed indicator on the open reads the bar open`() {
        val bag = IndicatorBinding.Bag()
        val binding = bag.bind(call("SMA", field("open"), num(2)))

        bag.updateAll(ctx("10", "99"))
        bag.updateAll(ctx("20", "99"))

        assertThat(binding.indicator.value()).isEqualByComparingTo("15")
    }

    @Test
    fun `a stream-fed indicator on a field with no candle value fails on its first update`() {
        val bag = IndicatorBinding.Bag()
        bag.bind(call("SMA", field("open_interest"), num(2)))

        assertThatThrownBy { bag.updateAll(ctx("1", "1")) }
            .hasMessageContaining("Numeric indicator on stream 'b' requires a numeric field; got 'open_interest'")
    }

    @Test
    fun `a k-series binding feeds aligned tuples and skips bars where any series is undefined`() {
        val seen = mutableListOf<List<BigDecimal>>()
        val multi =
            object : MultiIndicator {
                override fun update(values: List<BigDecimal>) {
                    seen += values
                }

                override fun value(): BigDecimal? = seen.lastOrNull()?.first()

                override val isReady: Boolean get() = seen.isNotEmpty()
                override val warmupBars: Int = 1
            }
        var second: Value = Value.Num(BigDecimal.ONE)
        val bag = IndicatorBinding.Bag()
        bag.bindMulti(
            call("RESID"),
            multi,
            listOf(CompiledExpr { Value.Num(it.candle.close) }, CompiledExpr { second }),
            "b",
        )

        bag.updateAll(ctx("5", "5"))
        second = Value.Undefined
        bag.updateAll(ctx("6", "6"))

        assertThat(seen).containsExactly(listOf(BigDecimal("5"), BigDecimal.ONE))
    }

    @Test
    fun `binding rejects an indicator output feeding an indicator that needs candles`() {
        assertThatThrownBy { IndicatorBinding.Bag().bind(call("ATR", call("SMA", field("close"), num(2)), num(14))) }
            .hasMessageContaining("Indicator ATR requires a candle series; cannot accept another indicator's output")
    }

    @Test
    fun `binding rejects an arithmetic series and a non-literal constant argument`() {
        assertThatThrownBy {
            IndicatorBinding.Bag().bind(
                call("EMA", BinaryOp(BinOp.ADD, field("close"), num(1)), num(3)),
            )
        }.hasMessageContaining("series arg must be a stream field, another indicator call")
        assertThatThrownBy { IndicatorBinding.Bag().bind(call("EMA", field("close"), field("open"))) }
            .hasMessageContaining("Indicator EMA non-series arg must be a numeric literal")
    }

    @Test
    fun `binding checks the series kind each indicator needs`() {
        assertThatThrownBy { IndicatorBinding.Bag().bind(call("ATR", field("close"), num(14))) }
            .hasMessageContaining("series arg must be the whole stream")
        assertThatThrownBy { IndicatorBinding.Bag().bind(call("RUNLENGTH_WHERE", field("close"))) }
            .hasMessageContaining("Indicator RUNLENGTH_WHERE requires a condition expression")
        assertThatThrownBy { IndicatorBinding.Bag().bind(call("VWAP", field("close"), num(20))) }
            .hasMessageContaining("Indicator VWAP requires a tick series; use vwap(b.tick, …)")
    }

    @Test
    fun `pair and expression bindings check arity, input kind and constant arguments`() {
        val series = CompiledExpr { Value.Num(BigDecimal.ONE) }

        assertThatThrownBy { IndicatorBinding.Bag().bindPair(call("CORRELATION", field("close")), series, series, "b") }
            .hasMessageContaining("Indicator CORRELATION expects 3 args, got 1")
        assertThatThrownBy { IndicatorBinding.Bag().bindPair(call("ATR", field("close"), num(3)), series, series, "b") }
            .hasMessageContaining("two-series binding only supports NUMERIC_SERIES")
        assertThatThrownBy { IndicatorBinding.Bag().bindExpression(call("EMA", field("close")), series, "b") }
            .hasMessageContaining("Indicator EMA expects 2 args, got 1")
        assertThatThrownBy {
            IndicatorBinding.Bag().bindExpression(
                call("EMA", field("close"), field("open")),
                series,
                "b",
            )
        }.hasMessageContaining("Indicator EMA non-series arg must be a numeric literal")
    }
}
