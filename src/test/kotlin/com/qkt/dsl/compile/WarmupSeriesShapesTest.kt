package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An indicator's stream is found wherever its series expression mentions it, not only in first place. */
class WarmupSeriesShapesTest {
    private fun warmup(series: String): Map<String, Int> {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            LET lifted = g.close + 1
            RULES
              WHEN ema($series, 20) > 0 THEN FLATTEN
            """.trimIndent()
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        return WarmupRequirements.compute(ast)
    }

    @Test
    fun `the stream is found on the right of an arithmetic or a negation`() {
        assertThat(warmup("1 - g.close")).containsEntry("g", 20)
        assertThat(warmup("-g.close")).containsEntry("g", 20)
    }

    @Test
    fun `the stream is found in any bound of a BETWEEN and any member of an IN list`() {
        assertThat(warmup("1 BETWEEN g.low AND 2")).containsEntry("g", 20)
        assertThat(warmup("1 BETWEEN 0 AND g.high")).containsEntry("g", 20)
        assertThat(warmup("1 IN [2, g.close]")).containsEntry("g", 20)
    }

    @Test
    fun `the stream is found on the right of a comparison or a cross`() {
        assertThat(warmup("1 > g.close")).containsEntry("g", 20)
        assertThat(warmup("1 CROSSES ABOVE g.close")).containsEntry("g", 20)
    }

    @Test
    fun `the stream is found in a CASE value, its ELSE, a function argument, a null test and a LET`() {
        assertThat(warmup("CASE WHEN 1 > 2 THEN g.close ELSE 0 END")).containsEntry("g", 20)
        assertThat(warmup("CASE WHEN 1 > 2 THEN 0 ELSE g.close END")).containsEntry("g", 20)
        assertThat(warmup("max(1, g.close)")).containsEntry("g", 20)
        assertThat(warmup("g.close IS NULL")).containsEntry("g", 20)
        assertThat(warmup("lifted")).containsEntry("g", 20)
    }
}
