package com.qkt.dsl.compile

import com.qkt.app.requireMarkPrices
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.marks.MarkPrices
import com.qkt.marketdata.marks.MarkSample
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `<alias>.mark` and `<alias>.index` read the data source's marks at evaluation time, and are refused where none are served. */
class MarkStreamFieldsTest {
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val asked = mutableListOf<Triple<String, Long, Long>>()
    private val marks =
        object : MarkPrices {
            override fun at(
                symbol: String,
                windowMs: Long,
                atMs: Long,
            ): MarkSample? {
                asked += Triple(symbol, windowMs, atMs)
                return MarkSample(atMs - 1, BigDecimal("86432.49"), BigDecimal("86403.5")).takeIf { atMs > 1_000 }
            }
        }

    private fun source(
        served: MarkPrices?,
        problem: String? = null,
    ) = object : MarketSource {
        override val name = "fake"
        override val capabilities = emptySet<MarketSourceCapability>()

        override fun supports(symbol: String) = true

        override fun marksFor(symbol: String): MarkPrices? =
            served?.let {
                object : MarkPrices by it {
                    override fun problem(symbol: String) = problem
                }
            }
    }

    private fun eval(
        field: String,
        at: Long,
    ): Value {
        val candle =
            Candle("x", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, at - 1, at)
        val ctx =
            EvalContext(
                candle = candle,
                streams = mapOf("perp" to HubKey("DERIBIT", "BTC_USDC_PERPETUAL", "15m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(source = source(marks)),
                evaluationTimeMs = at,
            )
        return ExprCompiler().compile(StreamFieldRef("perp", field)).evaluate(ctx)
    }

    private fun compiled(rule: String): DslCompiledStrategy {
        val source =
            "STRATEGY t VERSION 1\nSYMBOLS\n    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 15m\n" +
                "    gold = EXNESS:XAUUSD EVERY 15m\nRULES\n$rule\n"
        return AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as DslCompiledStrategy
    }

    @Test
    fun `mark and index are the source's values at evaluation time, read at the stream's window`() {
        assertThat(eval("mark", 2_000)).isEqualTo(Value.Num(BigDecimal("86432.49")))
        assertThat(eval("index", 2_000)).isEqualTo(Value.Num(BigDecimal("86403.5")))
        assertThat(asked.last()).isEqualTo(Triple(perp, 900_000L, 2_000L))
    }

    @Test
    fun `before any value is known they are undefined, so a rule reading them does not fire`() {
        assertThat(eval("mark", 1_000)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `the symbols a strategy reads marks of are collected from rules and actions alike`() {
        val strategy = compiled("    WHEN perp.mark - perp.index > 10\n    THEN LOG \"premium\" m=gold.close")
        val inAction = compiled("    WHEN gold.close > 0\n    THEN LOG \"basis\" b=perp.mark")

        assertThat(strategy.markSymbols).containsExactly(perp)
        assertThat(inAction.markSymbols).containsExactly(perp)
    }

    @Test
    fun `a strategy reading marks does not start on a feed that serves none, or serves them with a problem`() {
        val strategy = compiled("    WHEN perp.mark > 0\n    THEN LOG \"x\"")

        assertThatThrownBy { requireMarkPrices("s", strategy, source(null)) }
            .hasMessageContaining("reads the mark or index of $perp but its data feed ('fake') serves no mark prices")
        assertThatThrownBy { requireMarkPrices("s", strategy, source(marks, "its gateway does not serve mark prices")) }
            .hasMessageContaining("its gateway does not serve mark prices")
        requireMarkPrices("s", strategy, source(marks))
    }
}
