package com.qkt.app

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Fills on a structure's contracts that are not its own orders. */
internal class StructureBookExternalTest : StructureBookHarness() {
    @Test
    fun `a fill that is no structure's order closes legs held on the other side, oldest structure first`() {
        openSpread("ps")
        openSpread("qs")

        book.external(StructureFixtures.P81, Side.BUY, BigDecimal("0.15"), BigDecimal("700"))
        book.external(StructureFixtures.P78, Side.BUY, BigDecimal("0.1"), BigDecimal("300"))

        // ps's short is bought back whole at 700 (-0.1 x 54), qs's for the 0.05 left; a buy never closes a long.
        val ps = requireNotNull(book.live("ps")).legs
        assertThat(ps.first().heldQuantity).isEqualByComparingTo("0")
        assertThat(ps.first().realized).isEqualByComparingTo("-5.4")
        assertThat(requireNotNull(book.live("qs")).legs.first().heldQuantity).isEqualByComparingTo("-0.05")
        assertThat(ps.last().heldQuantity).isEqualByComparingTo("0.1")
    }

    @Test
    fun `a closing fill beyond what the leg still holds closes only what it holds`() {
        openSpread()
        book.external(StructureFixtures.P81, Side.BUY, BigDecimal("0.1"), BigDecimal("700"))
        close("ps", "c1" to StructureFixtures.P81)

        book.filled("c1", BigDecimal("0.1"), BigDecimal("710"))

        val short = requireNotNull(book.live("ps")).legs.first()
        assertThat(short.heldQuantity).isEqualByComparingTo("0")
        assertThat(short.realized).isEqualByComparingTo("-5.4")
    }

    @Test
    fun `a structure closed leg by leg from outside leaves the book`() {
        openSpread()

        book.external(StructureFixtures.P78, Side.SELL, BigDecimal("0.1"), BigDecimal("200"))
        book.external(StructureFixtures.P81, Side.BUY, BigDecimal("0.1"), BigDecimal("600"))

        assertThat(book.live("ps")).isNull()
    }
}
