package com.qkt.dsl.parse

import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogParserTest {
    private fun parseAction(action: String): com.qkt.dsl.ast.ActionAst {
        val full =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0
                THEN $action
            """.trimIndent()
        val parsed = (Dsl.parse(full) as ParseResult.Success).value
        return (parsed.rules.first() as com.qkt.dsl.ast.WhenThen).action
    }

    private fun parseFailure(action: String): ParseResult.Failure<com.qkt.dsl.ast.StrategyAst> {
        val full =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0
                THEN $action
            """.trimIndent()
        return Dsl.parse(full) as ParseResult.Failure
    }

    @Test
    fun `LOG literal parses with INFO and no fields`() {
        val action = parseAction("LOG 'hello'")
        assertThat(action).isEqualTo(Log(LogLevel.INFO, "hello", emptyMap()))
    }

    @Test
    fun `LOG WARN parses with WARN level`() {
        val action = parseAction("LOG WARN 'high'")
        assertThat(action).isEqualTo(Log(LogLevel.WARN, "high", emptyMap()))
    }

    @Test
    fun `LOG ERROR parses with ERROR level`() {
        val action = parseAction("LOG ERROR 'down'")
        assertThat(action).isEqualTo(Log(LogLevel.ERROR, "down", emptyMap()))
    }

    @Test
    fun `LOG DEBUG parses with DEBUG level`() {
        val action = parseAction("LOG DEBUG 'tick'")
        assertThat(action).isEqualTo(Log(LogLevel.DEBUG, "tick", emptyMap()))
    }

    @Test
    fun `LOG with single field parses`() {
        val action = parseAction("LOG 'buy' qty=1")
        assertThat(action).isEqualTo(
            Log(LogLevel.INFO, "buy", mapOf("qty" to NumLit(BigDecimal.ONE))),
        )
    }

    @Test
    fun `LOG with multiple fields parses in order`() {
        val action = parseAction("LOG 'trade' qty=1 price=2")
        assertThat(action).isEqualTo(
            Log(
                LogLevel.INFO,
                "trade",
                linkedMapOf(
                    "qty" to NumLit(BigDecimal.ONE),
                    "price" to NumLit(BigDecimal("2")),
                ),
            ),
        )
    }

    @Test
    fun `LOG with placeholder + matching field parses`() {
        val action = parseAction("LOG 'buy at {price}' price=1")
        assertThat(action).isEqualTo(
            Log(LogLevel.INFO, "buy at {price}", mapOf("price" to NumLit(BigDecimal.ONE))),
        )
    }

    @Test
    fun `LOG with placeholder but no matching field is rejected`() {
        val failure = parseFailure("LOG 'buy at {price}'")
        assertThat(failure.errors.joinToString { it.message }).contains("placeholder")
    }

    @Test
    fun `LOG with duplicate field names is rejected`() {
        val failure = parseFailure("LOG 'x' qty=1 qty=2")
        assertThat(failure.errors.joinToString { it.message }).contains("duplicate")
    }

    @Test
    fun `LOG with string literal field parses`() {
        val action = parseAction("LOG 'trade' side='BUY'")
        assertThat(action).isEqualTo(
            Log(LogLevel.INFO, "trade", mapOf("side" to StringLit("BUY"))),
        )
    }
}
