package com.qkt.marketdata.history

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnsupportedDataExceptionTest {
    @Test
    fun `message includes capability and provider class`() {
        val ex = UnsupportedDataException(DataCapability.TICKS, "FakeProvider")
        assertThat(ex.message).contains("TICKS")
        assertThat(ex.message).contains("FakeProvider")
    }

    @Test
    fun `is a RuntimeException`() {
        val ex = UnsupportedDataException(DataCapability.CANDLES_INTRADAY, "X")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }
}
