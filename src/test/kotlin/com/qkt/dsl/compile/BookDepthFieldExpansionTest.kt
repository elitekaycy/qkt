package com.qkt.dsl.compile

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `<alias>.bid_depth`, `.ask_depth` and `.book_imbalance` become hidden streams of their own, beside open interest. */
class BookDepthFieldExpansionTest {
    private fun parsed(rules: String): StrategyAst {
        val source =
            "STRATEGY depth VERSION 1\nSYMBOLS\n    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m WARMUP 5 BARS\nRULES\n$rules"
        return (Dsl.parse(source) as ParseResult.Success).value
    }

    @Test
    fun `each depth field read adds one hidden stream of the contract's book beside the traded stream`() {
        val ast =
            parsed(
                "    WHEN perp.book_imbalance > ema(perp.book_imbalance, 3) AND perp.ask_depth > 1\n" +
                    "        AND perp.open_interest > 0\n    THEN BUY perp SIZING 0.01\n",
            )

        assertThat(ast.streams.map { "${it.alias}=${it.qktSymbol}/${it.timeframe}/${it.warmupBars}" })
            .containsExactly(
                "perp=DERIBIT:BTC_USDC_PERPETUAL/1m/5",
                "perp/open_interest=OI:DERIBIT:BTC_USDC_PERPETUAL/1m/5",
                "perp/book_imbalance=DEPTH:IMBALANCE:DERIBIT:BTC_USDC_PERPETUAL/1m/5",
                "perp/ask_depth=DEPTH:ASK:DERIBIT:BTC_USDC_PERPETUAL/1m/5",
            )
        assertThat(ast.rules.toString()).doesNotContain("book_imbalance)").contains("perp/book_imbalance")
        assertThat(BookDepthFieldExpansion.apply(ast)).isEqualTo(ast)
    }

    @Test
    fun `the compiler accepts depth on a venue stream and refuses it by name where no venue series exists`() {
        val compiled = AstCompiler().compile(parsed("    WHEN perp.bid_depth > 10 THEN BUY perp SIZING 0.01\n"))
        val basket =
            "STRATEGY depth VERSION 1\nSYMBOLS\n    a = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m,\n" +
                "    b = DERIBIT:ETH_USDC_PERPETUAL EVERY 1m,\n    ab = BASKET EQUAL_WEIGHT [a, b] EVERY 1m\n" +
                "RULES\n    WHEN ab.book_imbalance > 0 THEN BUY a SIZING 0.01\n"

        assertThat((compiled as DslCompiledStrategy).declaredStreams.keys).contains("perp/bid_depth")
        assertThatThrownBy { AstCompiler().compile((Dsl.parse(basket) as ParseResult.Success).value) }
            .hasMessageContaining("ab.book_imbalance reads a venue's published series")
        assertThat(DslVocabulary.candleFields).contains("bid_depth", "ask_depth", "book_imbalance")
        assertThat(DslVocabulary.numericCandleFields).contains("bid_depth", "ask_depth", "book_imbalance")
    }

    @Test
    fun `a rule reading depth runs on its contract's closes, never on the depth streams themselves`() {
        val streams =
            mapOf(
                "perp" to HubKey("DERIBIT", "BTC_USDC_PERPETUAL", "1m"),
                "perp/bid_depth" to HubKey("DEPTH", "BID:DERIBIT:BTC_USDC_PERPETUAL", "1m"),
                "perp/book_imbalance" to HubKey("DEPTH", "IMBALANCE:DERIBIT:BTC_USDC_PERPETUAL", "1m"),
                "perp/open_interest" to HubKey("OI", "DERIBIT:BTC_USDC_PERPETUAL", "1m"),
            )

        assertThat(RuleTriggers.runsOn(setOf("perp/bid_depth", "perp/book_imbalance"), streams)).containsExactly("perp")
        assertThat(RuleTriggers.runsOn(setOf("perp/bid_depth", "perp/open_interest"), streams))
            .containsExactly("perp", "perp/open_interest")
    }
}
