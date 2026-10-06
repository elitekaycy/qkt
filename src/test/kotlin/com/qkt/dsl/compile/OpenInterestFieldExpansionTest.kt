package com.qkt.dsl.compile

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `<alias>.open_interest` becomes a hidden stream of its own, so it replays and feeds live like any stream. */
class OpenInterestFieldExpansionTest {
    private fun parsed(rules: String): StrategyAst {
        val source =
            "STRATEGY oi VERSION 1\nSYMBOLS\n    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m WARMUP 5 BARS\nRULES\n$rules"
        return (Dsl.parse(source) as ParseResult.Success).value
    }

    @Test
    fun `a read of open interest adds one hidden stream of the contract's figures beside the traded stream`() {
        val ast =
            parsed(
                "    WHEN perp.open_interest > ema(perp.open_interest, 3) AND perp.open_interest[1] > 0\n" +
                    "    THEN BUY perp SIZING 0.01\n",
            )

        assertThat(ast.streams.map { "${it.alias}=${it.qktSymbol}/${it.timeframe}/${it.warmupBars}" })
            .containsExactly(
                "perp=DERIBIT:BTC_USDC_PERPETUAL/1m/5",
                "perp/open_interest=OI:DERIBIT:BTC_USDC_PERPETUAL/1m/5",
            )
        assertThat(ast.rules.toString()).doesNotContain("open_interest)").contains("perp/open_interest")
        assertThat(OpenInterestFieldExpansion.apply(ast)).isEqualTo(ast)
    }

    @Test
    fun `a strategy that never reads open interest is unchanged`() {
        val ast = parsed("    WHEN perp.close > 0 THEN BUY perp SIZING 0.01\n")

        assertThat(ast.streams.map { it.alias }).containsExactly("perp")
    }

    @Test
    fun `the compiler accepts it on a venue stream and refuses it by name where no venue series exists`() {
        val compiled = AstCompiler().compile(parsed("    WHEN perp.open_interest > 1000 THEN BUY perp SIZING 0.01\n"))
        val basket =
            "STRATEGY oi VERSION 1\nSYMBOLS\n    a = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m,\n" +
                "    b = DERIBIT:ETH_USDC_PERPETUAL EVERY 1m,\n    ab = BASKET EQUAL_WEIGHT [a, b] EVERY 1m\n" +
                "RULES\n    WHEN ab.open_interest > 0 THEN BUY a SIZING 0.01\n"

        assertThat((compiled as DslCompiledStrategy).declaredStreams.keys).contains("perp/open_interest")
        assertThatThrownBy { AstCompiler().compile((Dsl.parse(basket) as ParseResult.Success).value) }
            .hasMessageContaining("ab.open_interest reads a venue's published series")
        assertThat(DslVocabulary.candleFields).contains("open_interest")
        assertThat(DslVocabulary.numericCandleFields).contains("open_interest")
    }
}
