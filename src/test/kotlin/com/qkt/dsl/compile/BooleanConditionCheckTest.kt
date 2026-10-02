package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** A WHEN that is a value rather than a test is a compile error, since it could never fire. */
class BooleanConditionCheckTest {
    private fun compile(condition: String) {
        val src =
            "STRATEGY s VERSION 1\nSYMBOLS\n    gold = EXNESS:XAUUSD EVERY 5m\nLET up = gold.close > gold.open\n" +
                "LET mid = (gold.high + gold.low) / 2\nRULES\n    WHEN $condition\n    THEN BUY gold SIZING 0.1\n"
        val ast = (Dsl.parse(src) as? ParseResult.Success ?: error("parse failed: ${Dsl.parse(src)}")).value
        AstCompiler().compile(ast)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "gold.close", "rsi(gold.close, 14)", "gold.close - gold.open", "1", "mid",
            "gold.close > 1 AND gold.volume", "NOT gold.close",
        ],
    )
    fun `a value is refused as a condition`(condition: String) {
        assertThatThrownBy { compile(condition) }.hasMessageContaining("WHEN needs a test, not a value")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "gold.close > 1", "up", "NOT up AND gold.close > 1", "rsi(gold.close, 14) < 30 OR up",
            "gold.bid IS NULL", "gold.close BETWEEN 1 AND 2", "TRUE",
        ],
    )
    fun `a test is accepted`(condition: String) {
        assertThatCode { compile(condition) }.doesNotThrowAnyException()
    }
}
