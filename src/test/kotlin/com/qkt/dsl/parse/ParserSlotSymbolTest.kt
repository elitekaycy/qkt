package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An event slot (`KALSHI:KXINXU@front.p70`) parses into the stream's symbol; continuous futures parse as before. */
class ParserSlotSymbolTest {
    private fun parse(stream: String): ParseResult<StrategyAst> =
        Dsl.parse(
            """
            STRATEGY s VERSION 1
            SYMBOLS
                k = $stream EVERY 1m
            RULES
                WHEN k.close > 0 THEN LOG "x"
            """.trimIndent(),
        )

    private fun symbolOf(stream: String): String =
        (parse(stream) as ParseResult.Success)
            .value
            .streams
            .single()
            .qktSymbol

    private fun failure(stream: String): String = (parse(stream) as ParseResult.Failure).toString()

    @Test
    fun `every selector shape stays inside the symbol`() {
        assertThat(symbolOf("KALSHI:KXINXU@front.p70")).isEqualTo("KALSHI:KXINXU@front.p70")
        assertThat(symbolOf("KALSHI:KXINXU@front.atm")).isEqualTo("KALSHI:KXINXU@front.atm")
        assertThat(symbolOf("KALSHI:KXINXU@next.above2")).isEqualTo("KALSHI:KXINXU@next.above2")
        assertThat(symbolOf("KALSHI:KXINXU@next.below1")).isEqualTo("KALSHI:KXINXU@next.below1")
        assertThat(symbolOf("KALSHI:KXNBAGAME@front.top1")).isEqualTo("KALSHI:KXNBAGAME@front.top1")
    }

    @Test
    fun `continuous futures selectors parse exactly as before`() {
        assertThat(symbolOf("CME:ES@front")).isEqualTo("CME:ES@front")
        assertThat(symbolOf("CME:ES@next")).isEqualTo("CME:ES@next")
        assertThat(failure("CME:ES@later")).contains("unknown continuous selector '@later'")
    }

    @Test
    fun `an unknown event or strike selector fails naming the spellings`() {
        assertThat(failure("KALSHI:KXINXU@later.p70")).contains("unknown slot event selector '@later'")
        assertThat(failure("KALSHI:KXINXU@front.p99")).contains("unknown slot strike selector 'p99'")
        assertThat(failure("KALSHI:KXINXU@front.p99")).contains("pNN (p05 ... p95), atm, aboveN, belowN, topN")
        assertThat(failure("KALSHI:KXINXU@front.70")).contains("expected a slot strike selector after '@front.'")
    }
}
