package com.qkt.marketdata.source

import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketRequestTest {
    @Test
    fun `requires non empty symbols`() {
        assertThatThrownBy { MarketRequest(symbols = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requires from less than to when both set`() {
        val a = Instant.parse("2024-01-15T00:00:00Z")
        val b = Instant.parse("2024-01-16T00:00:00Z")
        assertThatThrownBy { MarketRequest(listOf("X"), from = b, to = a) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `null from and to are allowed`() {
        MarketRequest(listOf("X"))
        MarketRequest(listOf("X"), from = Instant.parse("2024-01-15T00:00:00Z"))
        MarketRequest(listOf("X"), to = Instant.parse("2024-01-15T00:00:00Z"))
    }
}
