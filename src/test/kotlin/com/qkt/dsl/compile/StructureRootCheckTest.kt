package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StructureRootCheckTest {
    private fun compile(symbols: String) =
        AstCompiler().compile(
            (
                Dsl.parse(
                    "STRATEGY s VERSION 1\nSYMBOLS\n$symbols\nRULES\n    WHEN p.close > 0\n" +
                        "    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { SELL PUT DELTA 0.25 DTE 30 TO 45, BUY PUT DELTA 0.10 SAME EXPIRY } SIZING 0.1\n",
                ) as ParseResult.Success
            ).value,
        )

    @Test
    fun `a structure needs its option root fed, so its legs never fall through to another broker`() {
        assertThatThrownBy { compile("    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h") }
            .isInstanceOf(CompileError::class.java)
            .hasMessageContaining("OPTIONS:DERIBIT.BTC_USDC")
        assertThatCode {
            compile("    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1h,\n    p = DERIBIT:BTC_USDC_26SEP26_84500_P EVERY 1h")
        }.doesNotThrowAnyException()
    }
}
