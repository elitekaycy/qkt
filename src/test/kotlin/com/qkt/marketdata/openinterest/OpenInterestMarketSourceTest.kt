package com.qkt.marketdata.openinterest

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OpenInterestMarketSourceTest {
    private val asked = mutableListOf<Triple<String, Long, Long>>()
    private val source =
        OpenInterestMarketSource(
            OpenInterestSource { symbol, fromMs, toMs ->
                asked += Triple(symbol, fromMs, toMs)
                listOf(OpenInterest(1_000L, BigDecimal("10.5")), OpenInterest(4_000L, BigDecimal("12")))
            },
        )

    private fun range(
        fromMs: Long,
        toMs: Long,
    ) = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))

    @Test
    fun `only open interest streams of a venue contract are supported`() {
        assertThat(source.supports("OI:BINANCE_UM:BTCUSDT")).isTrue()
        assertThat(source.supports("OI:BTCUSDT")).isFalse()
        assertThat(source.supports("BINANCE_UM:BTCUSDT")).isFalse()
    }

    @Test
    fun `warmup bars are flat candles of each figure inside the range`() {
        val bars = source.bars("OI:BINANCE_UM:BTCUSDT", TimeWindow(300_000L), range(0L, 4_000L)).toList()

        assertThat(bars).hasSize(1)
        assertThat(bars.single().close).isEqualByComparingTo("10.5")
        assertThat(bars.single().high).isEqualByComparingTo("10.5")
        assertThat(bars.single().startTime).isEqualTo(1_000L)
        assertThat(bars.single().endTime).isEqualTo(301_000L)
        assertThat(asked.single()).isEqualTo(Triple("BINANCE_UM:BTCUSDT", 0L, 3_999L))
    }

    @Test
    fun `a symbol that is not an open interest stream is refused`() {
        assertThatThrownBy { source.ticks("BINANCE_UM:BTCUSDT", range(0L, 1L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("OI:<VENUE>:<NAME>")
    }
}
