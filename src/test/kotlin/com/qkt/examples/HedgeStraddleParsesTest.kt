package com.qkt.examples

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Smoke test for `examples/hedge-straddle/hedge-straddle.qkt`. Verifies the canonical
 * starter example parses cleanly and carries the three per-leg `STACK_AT` clauses the
 * Phase 27 design calls for. Compile-time engine wiring is deferred — the example uses
 * `TIF GTD` whose engine-side `TimeInForce.GTD` variant lands in a later phase — so this
 * test parses but does not compile.
 */
class HedgeStraddleParsesTest {
    @Test
    fun `hedge-straddle qkt parses and carries 3 stack tiers per side`() {
        val src =
            Path
                .of("examples", "hedge-straddle", "hedge-straddle.qkt")
                .toFile()
                .readText()
        val r = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Success::class.java)
        val ast = (r as ParseResult.Success).value
        assertThat(ast.rules).hasSize(2) // OCO_ENTRY rule + winner-timeout rule

        val entryRule = ast.rules[0] as WhenThen
        val oco = entryRule.action as OcoEntry
        val buyLeg = oco.leg1 as Buy
        val sellLeg = oco.leg2 as Sell
        assertThat(buyLeg.opts.stackAts).hasSize(3)
        assertThat(sellLeg.opts.stackAts).hasSize(3)
    }
}
