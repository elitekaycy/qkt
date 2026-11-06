package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An intent can only place what a one-shot order can carry: no option structures and no STACK_AT tiers. */
class AdHocActionsRefusalTest {
    private val header = "STRATEGY bot VERSION 1\nSYMBOLS\n  s0 = EXNESS:XAUUSD EVERY 1m\nRULES\n"

    private fun parse(src: String) = (Dsl.parse(src) as ParseResult.Success).value

    private val adHoc =
        requireNotNull((AstCompiler().compile(parse("$header  WHEN 1 > 2 THEN BUY s0 SIZING 0.01")) as DslCompiledStrategy).adHoc)

    private fun action(text: String): ActionAst = (parse("$header  WHEN TRUE\n  THEN $text").rules.single() as WhenThen).action

    @Test
    fun `an intent cannot open an option structure`() {
        val open = action("OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 0.3 DTE 7 TO 14 } SIZING 1")

        assertThatThrownBy { adHoc.validate(open) }
            .hasMessageContaining("option structures cannot be opened by an intent")
    }

    @Test
    fun `an intent cannot carry STACK_AT tiers`() {
        val stacked =
            action(
                "BUY s0 SIZING 0.2 BRACKET { STOP LOSS BY 25, TAKE PROFIT BY 60 } " +
                    "STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }",
            )

        assertThatThrownBy { adHoc.validate(stacked) }.hasMessageContaining("STACK_AT is not available in an intent")
    }
}
