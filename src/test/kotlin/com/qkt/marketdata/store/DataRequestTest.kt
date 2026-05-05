package com.qkt.marketdata.store

import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DataRequestTest {
    @Test
    fun `requires non empty symbols`() {
        assertThatThrownBy { DataRequest(symbols = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requires from less than to when both set`() {
        val a = Instant.parse("2024-01-15T00:00:00Z")
        val b = Instant.parse("2024-01-16T00:00:00Z")
        assertThatThrownBy { DataRequest(listOf("X"), from = b, to = a) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `null from and to are allowed`() {
        DataRequest(listOf("X"))
        DataRequest(listOf("X"), from = Instant.parse("2024-01-15T00:00:00Z"))
        DataRequest(listOf("X"), to = Instant.parse("2024-01-15T00:00:00Z"))
    }
}
