package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserSymbolsTest {
    private fun parseAst(s: String) = (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value

    private fun parseStreams(s: String) = parseAst(s).streams

    @Test
    fun `parses single stream declaration`() {
        val streams = parseStreams("STRATEGY s VERSION 1\nSYMBOLS btc = BYBIT:BTCUSDT EVERY 1m")
        assertThat(streams).hasSize(1)
        with(streams[0]) {
            assertThat(alias).isEqualTo("btc")
            assertThat(broker).isEqualTo("BYBIT")
            assertThat(symbol).isEqualTo("BTCUSDT")
            assertThat(timeframe).isEqualTo("1m")
        }
    }

    @Test
    fun `parses multiple comma-separated streams`() {
        val streams =
            parseStreams(
                "STRATEGY s VERSION 1\nSYMBOLS\n  btc = BYBIT:BTCUSDT EVERY 1m,\n  gold = INTERACTIVE:XAUUSD EVERY 15m",
            )
        assertThat(streams).hasSize(2)
        assertThat(streams[1].alias).isEqualTo("gold")
        assertThat(streams[1].timeframe).isEqualTo("15m")
    }

    @Test
    fun `parses account equity series declaration`() {
        val ast =
            parseAst(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                  eq = SERIES ACCOUNT.EQUITY EVERY 1h
                """.trimIndent(),
            )

        assertThat(ast.streams.map { it.alias }).containsExactly("gold")
        assertThat(ast.series).hasSize(1)
        with(ast.series.single()) {
            assertThat(alias).isEqualTo("eq")
            assertThat(source).isEqualTo(com.qkt.dsl.ast.SeriesSource.ACCOUNT_EQUITY)
            assertThat(timeframe).isEqualTo("1h")
        }
    }

    @Test
    fun `rejects sub-minute series declaration`() {
        val result =
            Parser(
                Lexer(
                    """
                    STRATEGY s VERSION 1
                    SYMBOLS eq = SERIES ACCOUNT.EQUITY EVERY 30s
                    """.trimIndent(),
                ).tokenize(),
            ).parseStrategy()

        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        val errors = (result as ParseResult.Failure).errors
        assertThat(errors.single().message).contains("timeframe must be >= 1m")
    }
}
