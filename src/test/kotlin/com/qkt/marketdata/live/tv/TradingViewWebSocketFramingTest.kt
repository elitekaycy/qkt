package com.qkt.marketdata.live.tv

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewWebSocketFramingTest {
    @Test
    fun `encode prepends framing header with byte length`() {
        val payload = """{"m":"quote_create_session","p":["abc"]}"""
        val framed = TradingViewFraming.encode(payload)
        assertThat(framed).isEqualTo("~m~${payload.length}~m~$payload")
    }

    @Test
    fun `decodeAll splits concatenated frames`() {
        val a = """{"m":"qsd","p":["s",{}]}"""
        val b = "~h~1~h~"
        val concatenated = TradingViewFraming.encode(a) + TradingViewFraming.encode(b)
        val result = TradingViewFraming.decodeAll(concatenated)
        assertThat(result.frames).containsExactly(a, b)
        assertThat(result.leftover).isEmpty()
    }

    @Test
    fun `decodeAll returns leftover when frame is incomplete`() {
        val full = """{"m":"qsd","p":["s",{}]}"""
        val truncated = TradingViewFraming.encode(full).dropLast(5)
        val result = TradingViewFraming.decodeAll(truncated)
        assertThat(result.frames).isEmpty()
        assertThat(result.leftover).isEqualTo(truncated)
    }

    @Test
    fun `decodeAll throws on garbage prefix`() {
        assertThatThrownBy {
            TradingViewFraming.decodeAll("garbage~m~10~m~xxxxxxxxxx")
        }.isInstanceOf(TradingViewProtocolException::class.java)
    }
}
