package com.qkt.dsl.compile

import com.qkt.app.requireTradeFlow
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.SideVolumes
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `<alias>.buy_volume[n]` and its siblings read one closed bar's flow `n` bars back; the closing bar is refused. */
class FlowStreamFieldsTest {
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val bar = 900_000L
    private val asked = mutableListOf<Triple<FlowKind, Long, Long>>()
    private val flow =
        object : TradeFlow {
            override fun window(
                symbol: String,
                kind: FlowKind,
                windowMs: Long,
                startMs: Long,
            ): SideVolumes? {
                asked += Triple(kind, windowMs, startMs)
                if (startMs < 0) return null
                return if (kind == FlowKind.TRADES) {
                    SideVolumes(BigDecimal("1.25"), BigDecimal("0.5"))
                } else {
                    SideVolumes(BigDecimal("0.01"), BigDecimal("0.3"))
                }
            }
        }

    private fun source(
        served: TradeFlow?,
        problem: String? = null,
    ) = object : MarketSource {
        override val name = "fake"
        override val capabilities = emptySet<MarketSourceCapability>()

        override fun supports(symbol: String) = true

        override fun tradeFlowFor(symbol: String): TradeFlow? =
            served?.let {
                object : TradeFlow by it {
                    override fun problem(
                        symbol: String,
                        kind: FlowKind,
                    ) = problem
                }
            }
    }

    private fun eval(
        field: String,
        back: Int,
        at: Long,
    ): Value {
        val candle =
            Candle("x", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, at - bar, at)
        val ctx =
            EvalContext(
                candle = candle,
                streams = mapOf("perp" to HubKey("DERIBIT", "BTC_USDC_PERPETUAL", "15m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(source = source(flow)),
                evaluationTimeMs = at,
            )
        val call = IndicatorCall("LAG", listOf(StreamFieldRef("perp", field), NumLit(BigDecimal(back))))
        return ExprCompiler().compile(call).evaluate(ctx)
    }

    private fun compiled(rule: String): DslCompiledStrategy {
        val source =
            "STRATEGY t VERSION 1\nSYMBOLS\n    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 15m\n" +
                "    gold = EXNESS:XAUUSD EVERY 15m\nRULES\n$rule\n"
        return AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as DslCompiledStrategy
    }

    @Test
    fun `x_buy_volume one bar back is the bar before the one closing, at the stream's window`() {
        val close = 10 * bar

        assertThat(eval("buy_volume", 1, close)).isEqualTo(Value.Num(BigDecimal("1.25")))
        assertThat(asked.last()).isEqualTo(Triple(FlowKind.TRADES, bar, 8 * bar))
        assertThat(eval("sell_volume", 3, close)).isEqualTo(Value.Num(BigDecimal("0.5")))
        assertThat(asked.last().third).isEqualTo(6 * bar)
    }

    @Test
    fun `liquidated longs are the liquidations that sold, shorts those that bought`() {
        assertThat(eval("long_liq_volume", 1, 10 * bar)).isEqualTo(Value.Num(BigDecimal("0.3")))
        assertThat(eval("short_liq_volume", 1, 10 * bar)).isEqualTo(Value.Num(BigDecimal("0.01")))
        assertThat(asked.map { it.first }.distinct()).containsExactly(FlowKind.LIQUIDATIONS)
    }

    @Test
    fun `a window whose prints are not known is undefined, so a rule reading it does not fire`() {
        assertThat(eval("buy_volume", 1, bar)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `the bar being closed is refused, naming the lookback that is allowed`() {
        assertThatThrownBy { compiled("    WHEN perp.buy_volume > 0\n    THEN LOG \"x\"") }
            .hasMessageContaining("perp.buy_volume is the flow of the bar being closed")
            .hasMessageContaining("read perp.buy_volume[1]")
    }

    @Test
    fun `a lookback in a rolling average compiles, and the series read are collected`() {
        val strategy =
            compiled(
                "    WHEN avg(perp.buy_volume[1], 4) > perp.sell_volume[2] AND perp.long_liq_volume[1] > 0\n" +
                    "    THEN LOG \"flow\" g=gold.close",
            )

        assertThat((strategy as TradeFlowReader).flowReads)
            .containsExactlyInAnyOrder(FlowRead(perp, FlowKind.TRADES), FlowRead(perp, FlowKind.LIQUIDATIONS))
    }

    @Test
    fun `a strategy reading flow does not start on a feed that serves none, or serves it with a problem`() {
        val strategy = compiled("    WHEN perp.buy_volume[1] > perp.sell_volume[1]\n    THEN LOG \"x\"")

        assertThatThrownBy { requireTradeFlow("s", strategy, source(null)) }
            .hasMessageContaining("reads the trades of $perp but its data feed ('fake') serves no trade tape")
        assertThatThrownBy { requireTradeFlow("s", strategy, source(flow, "its gateway does not serve trades")) }
            .hasMessageContaining("its gateway does not serve trades")
        requireTradeFlow("s", strategy, source(flow))
    }
}
