package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * #267 — LET references used inside a LATCH (offset, retrace distances, bracket SL/TP) must be
 * inlined to literals before compilation. Without the action-level LET resolution, the latch
 * distance compiler rejects a `Ref` ("LATCH distances must be compile-time constants"), which is
 * exactly what blocked the latch-stack production strategy.
 */
class LatchLetResolutionTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `LET-bound offset and bracket distances inside a latch compile`() {
        val src =
            """
            STRATEGY latchlet VERSION 1
            SYMBOLS
              gold = X:G EVERY 5m
            LET wire = 0.50,
                sl   = 13.50,
                tp   = 5.00
            RULES
              WHEN NOW.minute_utc = 55 AND POSITION.gold = 0
              THEN LATCH gold OFFSET wire ARM 1h {
                ENTER LIMIT RETRACE 4.70 BRACKET { STOP LOSS AGAINST sl, TAKE PROFIT WITH tp } SIZING 1.5 PCT RISK EXPIRE 1h
              }
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }
}
