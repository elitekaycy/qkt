package com.qkt.dsl.parse

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StructureLegAst
import com.qkt.dsl.ast.StructureLegRight
import com.qkt.dsl.ast.StructureLegSide
import com.qkt.dsl.ast.WhenThen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserStructureTest {
    private fun strategy(action: String) =
        "STRATEGY s VERSION 1\nSYMBOLS\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h\nRULES\n    WHEN chain.close > 0\n    THEN $action\n"

    private fun action(text: String): OpenStructure {
        val rule = (Dsl.parse(strategy(text)) as ParseResult.Success).value.rules.single() as WhenThen
        return rule.action as OpenStructure
    }

    @Test
    fun `a put spread with a DTE window and a same-expiry wing parses into legs`() {
        val ps =
            action(
                "OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { SELL PUT DELTA 0.25 DTE 30 TO 45, BUY PUT DELTA 0.10 SAME EXPIRY } SIZING 1 PCT RISK",
            )

        assertThat(ps.alias).isEqualTo("ps")
        assertThat(ps.root).isEqualTo("DERIBIT:BTC_USDC")
        assertThat(ps.legs).containsExactly(
            StructureLegAst(StructureLegSide.SELL, StructureLegRight.PUT, BigDecimal("0.25"), 30, 45),
            StructureLegAst(StructureLegSide.BUY, StructureLegRight.PUT, BigDecimal("0.10"), null, null),
        )
        assertThat(ps.sizing).isInstanceOf(SizeRiskFrac::class.java)
        assertThat(action("OPEN c = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 0.5 DTE 7 TO 14 } SIZING 0.1").sizing)
            .isEqualTo(SizeQty(NumLit(BigDecimal("0.1"))))
    }

    @Test
    fun `a first leg without a DTE window, an unknown word, a bad delta or window, or no SIZING is refused`() {
        val bad =
            listOf(
                "OPEN x = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 0.5 SAME EXPIRY } SIZING 0.1",
                "OPEN x = OPTIONS ON DERIBIT:BTC_USDC { BUY CALLS DELTA 0.5 DTE 7 TO 14 } SIZING 0.1",
                "OPEN x = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 1.5 DTE 7 TO 14 } SIZING 0.1",
                "OPEN x = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 0.5 DTE 14 TO 7 } SIZING 0.1",
                "OPEN x = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 0.5 DTE 7 TO 14 }",
            )

        for (text in bad) {
            assertThat(
                Dsl.parse(strategy(text)),
            ).describedAs(text).isInstanceOf(ParseResult.Failure::class.java)
        }
    }

    @Test
    fun `the leg words read in any case, like the keywords around them`() {
        val lower =
            action(
                "open ps = options on DERIBIT:BTC_USDC { sell put delta 0.25 dte 30 to 45, buy put delta 0.10 same expiry } sizing 0.1",
            )

        assertThat(lower.legs.map { it.right }).containsExactly(StructureLegRight.PUT, StructureLegRight.PUT)
        assertThat(lower.legs.last().minDays).isNull()
    }
}
