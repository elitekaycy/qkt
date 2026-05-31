package com.qkt.dsl.parse

import com.qkt.dsl.ast.SyncGroupDecl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — `SYNCHRONIZE <alias1> <alias2> [WITHIN <duration>]` clauses inside `SYMBOLS`.
 */
class ParserSyncSymbolsTest {
    private fun parseStrategy(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `single SYNCHRONIZE clause parses to one SyncGroupDecl`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h,
              silver = EXNESS:XAGUSD EVERY 1h
              SYNCHRONIZE gold silver
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.syncGroups).hasSize(1)
        assertThat(ast.syncGroups[0])
            .isEqualTo(SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = null))
    }

    @Test
    fun `multiple SYNCHRONIZE clauses parse to independent groups`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h,
              silver = EXNESS:XAGUSD EVERY 1h,
              btc    = BYBIT_SPOT:BTCUSDT EVERY 1h,
              eth    = BYBIT_SPOT:ETHUSDT EVERY 1h
              SYNCHRONIZE gold silver
              SYNCHRONIZE btc eth WITHIN 1s
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.syncGroups).hasSize(2)
        assertThat(ast.syncGroups[0])
            .isEqualTo(SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = null))
        assertThat(ast.syncGroups[1])
            .isEqualTo(SyncGroupDecl(aliases = listOf("btc", "eth"), timeoutMs = 1_000L))
    }

    @Test
    fun `strategy without SYNCHRONIZE has empty syncGroups`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1h
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.syncGroups).isEmpty()
    }

    @Test
    fun `SYNCHRONIZE with single alias is rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1h
              SYNCHRONIZE gold
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message).containsIgnoringCase("at least 2")
    }

    @Test
    fun `SYNCHRONIZE referencing an undeclared alias is rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1h
              SYNCHRONIZE gold mystery
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message)
            .containsIgnoringCase("mystery")
            .containsIgnoringCase("not declared")
    }

    @Test
    fun `overlapping SYNCHRONIZE groups are rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h,
              silver = EXNESS:XAGUSD EVERY 1h,
              btc    = BYBIT_SPOT:BTCUSDT EVERY 1h
              SYNCHRONIZE gold silver
              SYNCHRONIZE silver btc
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message)
            .containsIgnoringCase("silver")
            .containsIgnoringCase("more than one")
    }

    @Test
    fun `SYMBOLS without commas still parses every stream`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h
              silver = EXNESS:XAGUSD EVERY 1h
              SYNCHRONIZE gold silver
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.streams.map { it.alias }).containsExactly("gold", "silver")
        assertThat(ast.syncGroups).hasSize(1)
        assertThat(ast.syncGroups[0].aliases).containsExactly("gold", "silver")
    }
}
