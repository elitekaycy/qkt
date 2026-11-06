package com.qkt.marketdata.depth

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BookDepthMarketSourceTest {
    private val asked = mutableListOf<Triple<String, Long, Long>>()

    private fun level(amount: String) = BookLevel(BigDecimal("100"), BigDecimal(amount))

    private val books =
        listOf(
            BookDepth(1_000L, listOf(level("2"), level("3")), listOf(level("1"))),
            BookDepth(5_000L, listOf(level("4")), listOf(level("4"))),
            BookDepth(9_000L, listOf(level("1")), listOf(level("1"))),
        )

    private val source =
        BookDepthMarketSource(
            BookDepthSource { symbol, fromMs, toMs ->
                asked += Triple(symbol, fromMs, toMs)
                books
            },
        )

    private fun range(
        fromMs: Long,
        toMs: Long,
    ) = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))

    @Test
    fun `only well formed depth streams are supported`() {
        assertThat(source.supports("DEPTH:BID:DERIBIT:BTC-PERPETUAL")).isTrue()
        assertThat(source.supports("DEPTH:IMBALANCE:DERIBIT:BTC-PERPETUAL")).isTrue()
        assertThat(source.supports("DEPTH:MID:DERIBIT:BTC-PERPETUAL")).isFalse()
        assertThat(source.supports("DEPTH:BID:BTC")).isFalse()
        assertThat(source.supports("DERIBIT:BTC-PERPETUAL")).isFalse()
    }

    @Test
    fun `warmup bars are flat candles of each snapshot inside the range`() {
        val bars = source.bars("DEPTH:BID:DERIBIT:BTC-PERPETUAL", TimeWindow(60_000L), range(1_000L, 9_000L)).toList()

        assertThat(bars.map { it.startTime }).containsExactly(1_000L, 5_000L)
        assertThat(bars.map { it.close.toInt() }).containsExactly(5, 4)
        assertThat(bars.first().open).isEqualByComparingTo(bars.first().low)
        assertThat(bars.first().endTime).isEqualTo(61_000L)
        assertThat(asked.single()).isEqualTo(Triple("DERIBIT:BTC-PERPETUAL", 1_000L, 8_999L))
    }

    @Test
    fun `ask depth and imbalance ticks read their side of each book`() {
        val asks = source.ticks("DEPTH:ASK:DERIBIT:BTC-PERPETUAL", range(0L, 10_000L)).map { it.price.toInt() }
        val imbalance = source.ticks("DEPTH:IMBALANCE:DERIBIT:BTC-PERPETUAL", range(0L, 2_000L)).single()

        assertThat(asks.toList()).containsExactly(1, 4, 1)
        assertThat(imbalance.price).isEqualByComparingTo("0.66666667")
    }

    @Test
    fun `a symbol that is not a depth stream is refused naming the shape`() {
        assertThatThrownBy { source.ticks("DERIBIT:BTC-PERPETUAL", range(0L, 1L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DEPTH:<BID|ASK|IMBALANCE>:<VENUE>:<NAME>")
        assertThatThrownBy { BookDepthSymbol.value("DERIBIT:BTC-PERPETUAL", books.first()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("is not a depth stream")
    }
}
