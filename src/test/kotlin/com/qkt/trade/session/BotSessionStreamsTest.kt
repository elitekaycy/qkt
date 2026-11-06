package com.qkt.trade.session

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotSessionStreamsTest {
    @Test
    fun `every timeframe must be a whole multiple of the smallest`() {
        assertThatThrownBy { BotSessionStreams(listOf("EXNESS:XAUUSD"), listOf("2m", "3m")) }
            .hasMessage("timeframe 3m is not a whole multiple of the session's base 2m")
    }

    @Test
    fun `a session declares each symbol once, broker prefixed, with at least one timeframe`() {
        assertThatThrownBy { BotSessionStreams(emptyList(), listOf("1m")) }
            .hasMessage("a session needs at least one symbol")
        assertThatThrownBy { BotSessionStreams(listOf("EXNESS:XAUUSD"), emptyList()) }
            .hasMessage("a session needs at least one timeframe")
        assertThatThrownBy { BotSessionStreams(listOf("XAUUSD"), listOf("1m")) }
            .hasMessage("symbol must be BROKER:SYMBOL, got 'XAUUSD'")
        assertThatThrownBy { BotSessionStreams(listOf("EXNESS:XAUUSD", "EXNESS:XAUUSD"), listOf("1m")) }
            .hasMessage("a symbol is listed twice: [EXNESS:XAUUSD, EXNESS:XAUUSD]")
    }

    @Test
    fun `aliases and series keys name each symbol at each timeframe`() {
        val streams = BotSessionStreams(listOf("EXNESS:XAUUSD", "EXNESS:XAGUSD"), listOf("5m", "1m"))

        assertThat(streams.baseTf).isEqualTo("1m")
        assertThat(streams.alias("EXNESS:XAGUSD", "5m")).isEqualTo("s1_5m")
        assertThat(streams.alias("EXNESS:EURUSD")).isNull()
        assertThat(streams.alias("EXNESS:XAUUSD", "15m")).isNull()
        assertThat(streams.seriesOf("s1_5m")).isEqualTo("EXNESS:XAGUSD@5m")
        assertThat(streams.seriesOf("s9_1m")).isNull()
        assertThat(streams.series()).containsExactly(
            "EXNESS:XAUUSD@5m",
            "EXNESS:XAUUSD@1m",
            "EXNESS:XAGUSD@5m",
            "EXNESS:XAGUSD@1m",
        )
    }
}
