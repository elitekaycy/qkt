package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A name reference (a LET) in a rule that also reads a hub dataset passes through the field expansion untouched. */
class HubFieldExpansionRefTest {
    @Test
    fun `a LET reference beside a dataset field keeps working`() {
        val source =
            """
            STRATEGY hubLet VERSION 1

            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 5m
                cal  = HUB:cal.high_impact.USD EVERY 1d

            LET surprising = cal.surprise > 0.05

            RULES
                WHEN surprising THEN BUY gold SIZING 0.01
            """.trimIndent()

        val compiled = AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as DslCompiledStrategy

        assertThat(compiled.declaredStreams.keys.filter { it.startsWith("cal/") }).contains("cal/surprise")
    }
}
