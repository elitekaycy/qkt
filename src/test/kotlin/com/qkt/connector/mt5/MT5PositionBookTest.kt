package com.qkt.connector.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5PositionBookTest {
    private val book = MT5PositionBook()
    private val meta = MT5TicketMeta("dsl-gold--0", "gold_trend", MT5PositionProtection(BigDecimal("1990"), null))

    @Test
    fun `a tracked ticket resolves back to its order, symbol and open time`() {
        book.track(3258722072L, meta, "EXNESS:XAUUSD", 1_789_988_942_000L)

        assertThat(book.meta(3258722072L)).isEqualTo(meta)
        assertThat(book.symbol(3258722072L)).isEqualTo("EXNESS:XAUUSD")
        assertThat(book.openedAt(3258722072L)).isEqualTo(1_789_988_942_000L)
        assertThat(book.attributions()).containsExactly(java.util.Map.entry("3258722072", "gold_trend"))
    }

    @Test
    fun `an engine close forgets whose the ticket is but keeps its open time until told otherwise`() {
        book.track(7L, meta, "EXNESS:XAUUSD", 100L)

        book.forgetAttribution(7L)

        assertThat(book.isAttributed(7L)).isFalse()
        assertThat(book.symbol(7L)).isNull()
        assertThat(book.openedAt(7L)).isEqualTo(100L)
        book.forgetOpenedAt(7L)
        assertThat(book.openedAt(7L)).isNull()
    }

    @Test
    fun `a recovered position can be attributed before its open time is known`() {
        book.attribute(9L, meta)

        assertThat(book.isAttributed(9L)).isTrue()
        assertThat(book.openedAt(9L)).isNull()
    }

    @Test
    fun `protection is updated in place and only for a ticket that is attributed`() {
        book.attribute(9L, meta)

        book.updateMeta(9L) { it.copy(protection = MT5PositionProtection(BigDecimal("1995"), null)) }
        book.updateMeta(10L) { it.copy(orderId = "never") }

        assertThat(book.meta(9L)?.protection?.stopLoss).isEqualByComparingTo("1995")
        assertThat(book.meta(10L)).isNull()
    }

    @Test
    fun `forget removes every trace of the ticket`() {
        book.track(7L, meta, "EXNESS:XAUUSD", 100L)

        book.forget(7L)

        assertThat(book.isAttributed(7L)).isFalse()
        assertThat(book.openedAt(7L)).isNull()
        assertThat(book.attributions()).isEmpty()
    }

    @Test
    fun `a ticket can be claimed once and a second claimant is refused`() {
        val sibling = MT5TicketMeta("dsl-gold--1", "gold_trend")

        val first = book.claim(9L, meta)
        val second = book.claim(9L, sibling)

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(book.meta(9L)).isEqualTo(meta)
    }
}
