package com.qkt.app

import com.qkt.common.Side
import com.qkt.events.StructureClosed
import com.qkt.events.StructureOpened
import com.qkt.events.StructureOutcome
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class StructureBookTest : StructureBookHarness() {
    @Test
    fun `a structure is pending until every leg fills, then open with signed legs`() {
        book.accept(Signal.SubmitGroup("ps-1", "ps", listOf(shortPut, longPut)))
        book.filled("s", BigDecimal("0.1"), BigDecimal("646"))
        assertThat(book.live("ps")?.state).isEqualTo(StructureState.PENDING)

        book.filled("l", BigDecimal("0.1"), BigDecimal("219"))

        val ps = requireNotNull(book.live("ps"))
        assertThat(ps.state).isEqualTo(StructureState.OPEN)
        assertThat(ps.size).isEqualByComparingTo("0.1")
        assertThat(ps.legs.map { Triple(it.symbol, it.heldQuantity, it.entryPrice) }).containsExactly(
            Triple(StructureFixtures.P81, BigDecimal("-0.1"), BigDecimal("646")),
            Triple(StructureFixtures.P78, BigDecimal("0.1"), BigDecimal("219")),
        )
        assertThat(ps.legs.map { it.expiryMs }).containsOnly(StructureFixtures.OCT9)
    }

    @Test
    fun `closing fills realize premium P&L and the closed structure leaves the book`() {
        openSpread()
        close("ps", "c1" to StructureFixtures.P81, "c2" to StructureFixtures.P78)
        assertThat(book.live("ps")?.state).isEqualTo(StructureState.CLOSING)

        book.filled("c1", BigDecimal("0.1"), BigDecimal("300"))

        // Short sold at 646, bought back at 300: +0.1 x 346.
        val short = requireNotNull(book.live("ps")).legs.first()
        assertThat(short.realized).isEqualByComparingTo("34.6")
        assertThat(short.heldQuantity).isEqualByComparingTo("0")
        book.filled("c2", BigDecimal("0.1"), BigDecimal("100"))
        assertThat(book.live("ps")).isNull()
    }

    @Test
    fun `a close the venue rejects leaves the structure open, so it can be closed again`() {
        openSpread()
        close("ps", "c1" to StructureFixtures.P81)

        book.ended("c1")

        assertThat(book.live("ps")?.state).isEqualTo(StructureState.OPEN)
    }

    @Test
    fun `expiry settles each alias's legs on a shared contract by its own entry`() {
        openSpread("ps")
        book.accept(
            Signal.SubmitGroup("qs-1", "qs", listOf(StructureFixtures.market("q", StructureFixtures.P81, Side.BUY))),
        )
        book.filled("q", BigDecimal("0.1"), BigDecimal("600"))

        book.settleExpired(StructureFixtures.OCT9)

        // Delivered at 80000, the 81000 put pays 1000: ps's short from 646 -0.1 x 354, qs's long from 600 +0.1 x 400.
        val realized = published.filterIsInstance<StructureClosed>().associate { it.alias to it.realized }
        assertThat(realized.getValue("qs")).isEqualByComparingTo("40")
        assertThat(realized.getValue("ps")).isEqualByComparingTo("-57.3")
        assertThat(book.all()).isEmpty()
    }

    @Test
    fun `a group refused by risk sent nothing and is forgotten, a refused close keeps the structure open`() {
        book.accept(Signal.SubmitGroup("ps-1", "ps", listOf(shortPut, longPut)))
        book.refused("s")
        assertThat(book.live("ps")).isNull()

        openSpread("qs")
        close("qs", "c1" to StructureFixtures.P81)
        book.refused("c1")

        assertThat(book.live("qs")?.state).isEqualTo(StructureState.OPEN)
    }

    @Test
    fun `a close whose other leg the venue rejected reopens the structure once the rest fills`() {
        openSpread()
        close("ps", "c1" to StructureFixtures.P81, "c2" to StructureFixtures.P78)

        book.ended("c2")
        book.filled("c1", BigDecimal("0.1"), BigDecimal("300"))

        val ps = requireNotNull(book.live("ps"))
        assertThat(ps.state).isEqualTo(StructureState.OPEN)
        assertThat(ps.working).isFalse()
    }

    @Test
    fun `legs past expiry settle from the clock even when the venue nets them to nothing`() {
        openSpread("ps")
        book.accept(
            Signal.SubmitGroup("qs-1", "qs", listOf(StructureFixtures.market("q", StructureFixtures.P81, Side.BUY))),
        )
        book.filled("q", BigDecimal("0.1"), BigDecimal("600"))

        book.settleExpired(StructureFixtures.OCT9 - 1)
        assertThat(book.all()).hasSize(2)
        book.settleExpired(StructureFixtures.OCT9)

        // Delivered at 80000: the 81000 put pays 1000, the 78000 put nothing.
        assertThat(book.all()).isEmpty()
    }

    @Test
    fun `the size is what the legs filled, as a book scale resizes them`() {
        book.accept(Signal.SubmitGroup("ps-1", "ps", listOf(shortPut, longPut)))
        assertThat(requireNotNull(book.live("ps")).size).isEqualByComparingTo("0.1")

        book.filled("s", BigDecimal("0.05"), BigDecimal("646"))
        book.filled("l", BigDecimal("0.05"), BigDecimal("219"))

        assertThat(requireNotNull(book.live("ps")).size).isEqualByComparingTo("0.05")
    }

    @Test
    fun `the book reports a structure opening with its credit and leaving with its outcome and P&L`() {
        openSpread()
        close("ps", "c1" to StructureFixtures.P81, "c2" to StructureFixtures.P78)
        book.filled("c1", BigDecimal("0.1"), BigDecimal("300"))
        book.filled("c2", BigDecimal("0.1"), BigDecimal("100"))

        val opened = published.filterIsInstance<StructureOpened>().single()
        assertThat(opened.structureId).isEqualTo("ps-1")
        assertThat(opened.credit).isEqualByComparingTo("42.7")
        val closed = published.filterIsInstance<StructureClosed>().single()
        assertThat(closed.outcome).isEqualTo(StructureOutcome.CLOSED)
        // Short 646 -> 300: +34.6; long 219 -> 100: -11.9.
        assertThat(closed.realized).isEqualByComparingTo("22.7")
        assertThat(closed.strategyId).isEqualTo("st")
    }

    @Test
    fun `a structure whose last legs settle at expiry leaves as settled`() {
        openSpread()

        book.settleExpired(StructureFixtures.OCT9)

        val closed = published.filterIsInstance<StructureClosed>().single()
        assertThat(closed.outcome).isEqualTo(StructureOutcome.SETTLED)
        // Delivered at 80000: short 81000 put pays 1000 (-35.4), long 78000 put expires worthless (-21.9).
        assertThat(closed.realized).isEqualByComparingTo("-57.3")
    }
}
