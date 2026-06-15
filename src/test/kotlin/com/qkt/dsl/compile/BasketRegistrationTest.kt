package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BasketRegistrationTest {
    private fun compile(src: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private val src =
        """
        STRATEGY reg VERSION 1
        SYMBOLS
            aud = EXNESS:AUDUSD EVERY 1h
            nzd = EXNESS:NZDUSD EVERY 1h
            antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
        RULES
            WHEN aud.close > 0 THEN BUY aud SIZING 0.1
        """.trimIndent()

    @Test
    fun `basket alias is registered as a synthetic BASKET stream`() {
        val s = compile(src)
        assertThat(s.declaredStreams).containsKey("antipodean")
        assertThat(s.declaredStreams.getValue("antipodean"))
            .isEqualTo(HubKey("BASKET", "ANTIPODEAN", "1h"))
    }

    @Test
    fun `real constituents remain registered alongside the basket`() {
        val s = compile(src)
        assertThat(s.declaredStreams.getValue("aud")).isEqualTo(HubKey("EXNESS", "AUDUSD", "1h"))
        assertThat(s.declaredStreams.getValue("nzd")).isEqualTo(HubKey("EXNESS", "NZDUSD", "1h"))
    }

    @Test
    fun `retention covers the basket key`() {
        val s = compile(src)
        val basketKey = HubKey("BASKET", "ANTIPODEAN", "1h")
        assertThat(s.retentionByKey).containsKey(basketKey)
        assertThat(s.retentionByKey.getValue(basketKey)).isGreaterThanOrEqualTo(1)
    }
}
