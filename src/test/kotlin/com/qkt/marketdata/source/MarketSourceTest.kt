package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketSourceTest {
    private class BarsOnlySource : MarketSource {
        override val name = "BarsOnly"
        override val capabilities = setOf(MarketSourceCapability.BARS)

        override fun supports(symbol: String): Boolean = true
    }

    @Test
    fun `unsupported live ticks throws with capability and class`() {
        val src = BarsOnlySource()
        assertThatThrownBy { src.liveTicks(listOf("X")) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("LIVE_TICKS")
            .hasMessageContaining("BarsOnlySource")
    }

    @Test
    fun `unsupported ticks throws with capability and class`() {
        val src = BarsOnlySource()
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-16T00:00:00Z"),
            )
        assertThatThrownBy { src.ticks("X", range).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("TICKS")
    }

    @Test
    fun `capabilities reports only what is supported`() {
        val src = BarsOnlySource()
        assertThat(src.capabilities).containsExactly(MarketSourceCapability.BARS)
    }
}
