package com.qkt.marketdata.source

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnsupportedDataExceptionTest {
    @Test
    fun `message includes capability and provider class`() {
        val ex = UnsupportedDataException(MarketSourceCapability.TICKS, "FakeProvider")
        assertThat(ex.message).contains("TICKS")
        assertThat(ex.message).contains("FakeProvider")
    }

    @Test
    fun `is a RuntimeException`() {
        val ex = UnsupportedDataException(MarketSourceCapability.BARS, "X")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }
}
