package com.qkt.cli

import com.qkt.app.LiveSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `runtime.candle_close_grace_ms` is one key read by the live daemon and by replay (#1138). */
class ConfigCandleCloseGraceTest {
    @Test
    fun `defaults to the live heartbeat grace`() {
        assertThat(Config().candleCloseGraceMs).isEqualTo(LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS)
        assertThat(LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS).isEqualTo(2_000L)
    }

    @Test
    fun `reads the configured value`() {
        assertThat(Config(runtime = mapOf("candle_close_grace_ms" to " 500 ")).candleCloseGraceMs).isEqualTo(500L)
        assertThat(Config(runtime = mapOf("candle_close_grace_ms" to "0")).candleCloseGraceMs).isEqualTo(0L)
    }

    @Test
    fun `rejects a negative or non-numeric value`() {
        assertThatThrownBy { Config(runtime = mapOf("candle_close_grace_ms" to "-1")).candleCloseGraceMs }
            .hasMessageContaining("runtime.candle_close_grace_ms")
        assertThatThrownBy { Config(runtime = mapOf("candle_close_grace_ms" to "soon")).candleCloseGraceMs }
            .hasMessageContaining("runtime.candle_close_grace_ms")
    }
}
