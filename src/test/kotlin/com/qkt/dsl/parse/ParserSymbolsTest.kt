package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserSymbolsTest {
    private fun parseStreams(s: String) =
        (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value.streams

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
}
