package com.qkt.dsl.compile

import com.qkt.app.requireOptionMarks
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * `<alias>.iv` and the Greeks read the source's newest option quote at evaluation time, priced with Black-76
 * on its mark IV and forward; expected values come from an independent Black-76 (Python, `statistics.NormalDist`):
 * call 92000, forward 86000, IV 60, seven days to expiry, rate 0.
 */
class OptionStreamFieldsTest {
    private val call = "DERIBIT:BTC_USDC_2OCT26_92000_C"
    private val expiryMs = Instant.parse("2026-10-02T08:00:00Z").toEpochMilli()
    private val weekBefore = expiryMs - 7 * 86_400_000L
    private val quotedAt = weekBefore - 60_000L

    private fun registry(contractSize: String = "1") =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal(contractSize),
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
            maxQuoteAgeMinutes = 60,
        ).let { root ->
            val listing = OptionListing("BTC_USDC-2OCT26-92000-C", "92000", "call", expiryMs)
            OptionCatalogRegistry(
                listOf(root),
                mapOf(root.root to OptionCatalog(root.root, listOf(listing), emptyMap())),
                Path.of("unused"),
            )
        }

    private val quote =
        ChainQuote(
            quotedAt,
            "BTC_USDC-2OCT26-92000-C",
            BigDecimal("1150"),
            BigDecimal("1200"),
            BigDecimal("1175"),
            BigDecimal("60"),
            BigDecimal("86000"),
            null,
            0,
            QuoteSource.BOOK,
        )

    private fun marks(
        served: ChainQuote?,
        problem: String? = null,
    ) = object : OptionMarks {
        override fun at(
            qktSymbol: String,
            atMs: Long,
        ) = served?.takeIf { qktSymbol == call && it.atMs <= atMs }

        override fun problem(qktSymbol: String) = problem
    }

    private fun source(served: OptionMarks?) =
        object : MarketSource {
            override val name = "fake"
            override val capabilities = emptySet<MarketSourceCapability>()

            override fun supports(symbol: String) = true

            override fun optionMarksFor(symbol: String) = served
        }

    private fun eval(
        field: String,
        at: Long = weekBefore,
        contractSize: String = "1",
        served: ChainQuote? = quote,
    ): Value {
        val candle =
            Candle("x", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, at - 1, at)
        val ctx =
            EvalContext(
                candle = candle,
                streams = mapOf("c" to HubKey("DERIBIT", "BTC_USDC_2OCT26_92000_C", "1h")),
                lets = emptyMap(),
                strategyContext =
                    testStrategyContext(source = source(marks(served)), instruments = registry(contractSize)),
                evaluationTimeMs = at,
            )
        return ExprCompiler().compile(StreamFieldRef("c", field)).evaluate(ctx)
    }

    private fun num(value: Value): Double = (value as Value.Num).v.toDouble()

    @Test
    fun `iv is the quote's mark iv and the greeks are black-76 on it and its forward`() {
        assertThat(eval("iv")).isEqualTo(Value.Num(BigDecimal("60")))
        assertThat(num(eval("delta"))).isCloseTo(0.22061718762, Offset.offset(1e-9))
        assertThat(num(eval("gamma"))).isCloseTo(4.15024980654e-05, Offset.offset(1e-13))
        assertThat(num(eval("vega"))).isCloseTo(35.3205588467, Offset.offset(1e-6))
        assertThat(num(eval("theta"))).isCloseTo(-151.373823629, Offset.offset(1e-6))
    }

    @Test
    fun `the greeks are per contract, so they scale with the contract size`() {
        assertThat(num(eval("delta", contractSize = "10"))).isCloseTo(2.2061718762, Offset.offset(1e-8))
        assertThat(eval("iv", contractSize = "10")).isEqualTo(Value.Num(BigDecimal("60")))
    }

    @Test
    fun `before a quote, once it is older than the root's quote age, and at expiry they are undefined`() {
        assertThat(eval("iv", served = null)).isEqualTo(Value.Undefined)
        assertThat(eval("delta", at = quotedAt + 60 * 60_000L)).isNotEqualTo(Value.Undefined)
        assertThat(eval("delta", at = quotedAt + 60 * 60_000L + 1)).isEqualTo(Value.Undefined)
        assertThat(eval("iv", served = quote.copy(markIv = null))).isEqualTo(Value.Undefined)
        assertThat(eval("iv", at = expiryMs, served = quote.copy(atMs = expiryMs))).isEqualTo(Value.Undefined)
    }

    private fun compiled(rule: String): DslCompiledStrategy {
        val text =
            "STRATEGY t VERSION 1\nSYMBOLS\n    c = DERIBIT:BTC_USDC_2OCT26_92000_C EVERY 1h\n" +
                "    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1h\nRULES\n$rule\n"
        return AstCompiler().compile((Dsl.parse(text) as ParseResult.Success).value) as DslCompiledStrategy
    }

    @Test
    fun `the symbols a strategy reads option marks of are collected from rules and actions alike`() {
        val inRule = compiled("    WHEN c.delta > 0.5 AND c.iv < 40\n    THEN LOG \"rich\" p=perp.close")
        val inAction = compiled("    WHEN perp.close > 0\n    THEN LOG \"greeks\" v=c.vega t=c.theta g=c.gamma")
        val neither = compiled("    WHEN perp.close > 0\n    THEN LOG \"price\" p=c.close")

        assertThat(inRule.optionMarkSymbols).containsExactly(call)
        assertThat(inAction.optionMarkSymbols).containsExactly(call)
        assertThat(neither.optionMarkSymbols).isEmpty()
    }

    @Test
    fun `a strategy reading them does not start on a non-option, a feed without them, or one with a problem`() {
        val reads = compiled("    WHEN c.iv > 0\n    THEN LOG \"x\"")
        val onPerp = compiled("    WHEN perp.delta > 0\n    THEN LOG \"x\"")
        val instruments = registry()

        assertThatThrownBy { requireOptionMarks("s", onPerp, source(marks(quote)), instruments) }
            .hasMessageContaining("Greeks of DERIBIT:BTC_USDC_PERPETUAL but it is not a catalogued option contract")
        assertThatThrownBy { requireOptionMarks("s", reads, source(null), instruments) }
            .hasMessageContaining("Greeks of $call but its data feed ('fake') serves no option marks")
        assertThatThrownBy { requireOptionMarks("s", reads, source(marks(quote, "no option_marks")), instruments) }
            .hasMessageContaining("but no option_marks")
        requireOptionMarks("s", reads, source(marks(quote)), instruments)
    }
}
