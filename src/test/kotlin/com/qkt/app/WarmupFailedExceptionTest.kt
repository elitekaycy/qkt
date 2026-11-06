package com.qkt.app

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupFailedExceptionTest {
    @Test
    fun `the message names the stream, the symbol and the venue's reason`() {
        val cause = IOException("timeframe 7m not supported")

        val e = WarmupFailedException("gold", "EXNESS:XAUUSD", cause)

        assertThat(e.streamAlias).isEqualTo("gold")
        assertThat(e.qktSymbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(e.cause).isSameAs(cause)
        assertThat(e.message)
            .contains("stream 'gold' (EXNESS:XAUUSD)")
            .contains("broker historical API returned: timeframe 7m not supported.")
            .contains("change the stream's EVERY window")
    }

    @Test
    fun `a cause without a message is named by its type`() {
        val e = WarmupFailedException("gold", "EXNESS:XAUUSD", IllegalStateException())

        assertThat(e.message).contains("broker historical API returned: IllegalStateException.")
    }
}
