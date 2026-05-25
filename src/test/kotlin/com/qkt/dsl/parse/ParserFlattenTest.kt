package com.qkt.dsl.parse

import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserFlattenTest {
    @Test
    fun `FLATTEN parses to CloseAll`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN NOW.hour_utc = 21 THEN FLATTEN
        """.trimIndent()
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        val rule = ast.rules.single() as WhenThen
        assertThat(rule.action).isEqualTo(CloseAll)
    }

    @Test
    fun `CLOSE_ALL still parses to CloseAll`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN NOW.hour_utc = 21 THEN CLOSE_ALL
        """.trimIndent()
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        val rule = ast.rules.single() as WhenThen
        assertThat(rule.action).isEqualTo(CloseAll)
    }
}
