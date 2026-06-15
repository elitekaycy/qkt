package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BasketValidationTest {
    private fun compile(src: String) {
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)
    }

    @Test
    fun `a well-formed basket declaration passes validation`() {
        // The rule references a real stream (not the basket): basket registration as a
        // readable stream lands later — this task only validates the declaration itself.
        assertThatCode {
            compile(
                """
                STRATEGY ok VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN LOG "warm"
                """.trimIndent(),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `unbound constituent is rejected`() {
        assertThatThrownBy {
            compile(
                """
                STRATEGY bad VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN LOG "warm"
                """.trimIndent(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nzd")
    }

    @Test
    fun `basket of baskets is rejected`() {
        assertThatThrownBy {
            compile(
                """
                STRATEGY bad VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    inner = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
                    outer = BASKET EQUAL_WEIGHT [aud, inner] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN LOG "warm"
                """.trimIndent(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("inner")
    }

    @Test
    fun `timeframe mismatch with a constituent is rejected`() {
        assertThatThrownBy {
            compile(
                """
                STRATEGY bad VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 4h
                    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
                RULES
                    WHEN aud.close > 0 THEN LOG "warm"
                """.trimIndent(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeframe")
    }
}
