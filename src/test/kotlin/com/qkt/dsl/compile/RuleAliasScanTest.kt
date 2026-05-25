package com.qkt.dsl.compile

import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleAliasScanTest {
    private fun rule(src: String): WhenThen {
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        return ast.rules.single() as WhenThen
    }

    @Test
    fun `condition referencing one stream returns that alias`() {
        val r =
            rule(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN g.close > 100 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(collectStreamAliases(r)).containsExactly("g")
    }

    @Test
    fun `condition referencing position returns that alias`() {
        val r =
            rule(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN POSITION.g = 0 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(collectStreamAliases(r)).containsExactly("g")
    }

    @Test
    fun `multi-stream condition returns all referenced aliases`() {
        val r =
            rule(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  a = X:Y EVERY 1m,
                  b = X:Z EVERY 1m
                RULES
                  WHEN a.close > b.close THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(collectStreamAliases(r)).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `action's BUY target alias is included`() {
        val r =
            rule(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN NOW.hour_utc = 10 THEN BUY g
                """.trimIndent(),
            )
        assertThat(collectStreamAliases(r)).containsExactly("g")
    }

    @Test
    fun `NOW-only condition with no action target returns empty`() {
        val r =
            rule(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN NOW.hour_utc = 10 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(collectStreamAliases(r)).isEmpty()
    }
}
