package com.qkt.marketdata.live.bybit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitPublicWsBackoffTest {
    private val ws = BybitPublicWs(url = "ws://localhost:0")

    @Test
    fun `backoffSeconds caps at 30 and ramps 1 2 4 8 16 30`() {
        // Use reflection so we can exercise the private function without exposing it.
        val m = BybitPublicWs::class.java.getDeclaredMethod("backoffSeconds", Int::class.java)
        m.isAccessible = true
        assertThat(m.invoke(ws, 1)).isEqualTo(1L)
        assertThat(m.invoke(ws, 2)).isEqualTo(2L)
        assertThat(m.invoke(ws, 3)).isEqualTo(4L)
        assertThat(m.invoke(ws, 4)).isEqualTo(8L)
        assertThat(m.invoke(ws, 5)).isEqualTo(16L)
        assertThat(m.invoke(ws, 6)).isEqualTo(30L)
        assertThat(m.invoke(ws, 7)).isEqualTo(30L)
        assertThat(m.invoke(ws, 100)).isEqualTo(30L)
    }
}
