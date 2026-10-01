package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StructureChecksTest {
    private val open =
        "OPEN ps = OPTIONS ON DERIBIT:BTC_USDC " +
            "{ SELL PUT DELTA 0.25 DTE 30 TO 45, BUY PUT DELTA 0.10 SAME EXPIRY } SIZING 0.1"
    private val fed =
        "    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h,\n    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h"

    private fun compile(
        symbols: String,
        then: String = open,
    ) = AstCompiler().compile(
        (
            Dsl.parse("STRATEGY s VERSION 1\nSYMBOLS\n$symbols\nRULES\n    WHEN p.close > 0\n    THEN $then\n")
                as ParseResult.Success
        ).value,
    )

    @Test
    fun `a structure needs its option root fed, so its legs never fall through to another broker`() {
        assertThatThrownBy { compile("    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h") }
            .isInstanceOf(CompileError::class.java)
            .hasMessageContaining("OPTIONS:DERIBIT.BTC_USDC")
        assertThatCode { compile(fed) }.doesNotThrowAnyException()
    }

    @Test
    fun `a structure alias cannot name a stream`() {
        assertThatThrownBy { compile(fed, open.replace("OPEN ps", "OPEN p")) }
            .isInstanceOf(CompileError::class.java)
            .hasMessage("structure p has the name of a stream or basket")
    }

    @Test
    fun `one rule may open a structure after another action, but not the same alias twice`() {
        assertThatCode { compile(fed, "LOG \"open\"; $open") }.doesNotThrowAnyException()
        assertThatThrownBy { compile(fed, "$open; $open") }
            .isInstanceOf(CompileError::class.java)
            .hasMessage("one rule opens structure ps twice")
    }
}
