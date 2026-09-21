package com.qkt.connector.mt5

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5PendingBookTest {
    private val book = MT5PendingBook()
    private val meta = MT5TicketMeta("dsl-gold--1", "gold_trend")

    @Test
    fun `a registered order is cancellable by id and attributable by ticket`() {
        book.register(3258722177L, meta)

        assertThat(book.ticketOf("dsl-gold--1")).isEqualTo(3258722177L)
        assertThat(book.meta(3258722177L)).isEqualTo(meta)
        assertThat(book.isPending(3258722177L)).isTrue()
        assertThat(book.stillIs("dsl-gold--1", 3258722177L, meta)).isTrue()
    }

    @Test
    fun `a fill seen during recovery is attributed without becoming cancellable`() {
        book.attribute(7L, meta)

        assertThat(book.requireMeta(7L)).isEqualTo(meta)
        assertThat(book.ticketOf("dsl-gold--1")).isNull()
    }

    @Test
    fun `taking the owner of a ticket that became a position leaves the id link for the caller`() {
        book.register(7L, meta)

        assertThat(book.takeMeta(7L)).isEqualTo(meta)
        assertThat(book.takeMeta(7L)).`as`("a second poll round must not publish the fill again").isNull()
        assertThat(book.ticketOf("dsl-gold--1")).isEqualTo(7L)
        book.forgetOrderId("dsl-gold--1")
        assertThat(book.ticketOf("dsl-gold--1")).isNull()
    }

    @Test
    fun `a conditional forget leaves a newer registration of the same order alone`() {
        book.register(7L, meta)
        book.register(8L, meta)

        book.forgetIfStill("dsl-gold--1", 7L, meta)

        assertThat(book.isPending(7L)).isFalse()
        assertThat(book.ticketOf("dsl-gold--1")).`as`("the order now rests as ticket 8").isEqualTo(8L)
        assertThat(book.stillIs("dsl-gold--1", 7L, meta)).isFalse()
    }

    @Test
    fun `a ticket gone from the venue takes every id that pointed at it`() {
        book.register(7L, meta)

        book.forgetTicket(7L)

        assertThat(book.isPending(7L)).isFalse()
        assertThat(book.ticketOf("dsl-gold--1")).isNull()
        assertThatThrownBy { book.requireMeta(7L) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `an OCO rollback drops a leg by both keys`() {
        book.register(7L, meta)

        book.forgetLeg("dsl-gold--1", 7L)

        assertThat(book.isPending(7L)).isFalse()
        assertThat(book.ticketOf("dsl-gold--1")).isNull()
    }
}
