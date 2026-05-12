package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LegBookTest {
    private fun leg(
        legId: String,
        side: Side,
        qty: String,
        entry: String,
        role: LegRole = LegRole.PRIMARY,
        parentLegId: String? = if (role == LegRole.STACK) "parent" else null,
        openedAt: Long = 0L,
    ) = PositionLeg(
        legId = legId,
        symbol = "EURUSD",
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = openedAt,
        role = role,
        parentLegId = parentLegId,
    )

    @Test
    fun `empty book has null netView and zero netQuantity`() {
        val book = LegBook("EURUSD")
        assertThat(book.isEmpty()).isTrue
        assertThat(book.size()).isEqualTo(0)
        assertThat(book.netView()).isNull()
        assertThat(book.netQuantity()).isEqualByComparingTo("0")
        assertThat(book.primary()).isNull()
        assertThat(book.stacks()).isEmpty()
    }

    @Test
    fun `single PRIMARY BUY leg sets net and view`() {
        val book = LegBook("EURUSD")
        book.add(leg("p1", Side.BUY, "0.1", "1.10"))
        assertThat(book.netQuantity()).isEqualByComparingTo("0.1")
        val view = book.netView()!!
        assertThat(view.quantity).isEqualByComparingTo("0.1")
        assertThat(view.avgEntryPrice).isEqualByComparingTo("1.10")
        assertThat(book.primary()?.legId).isEqualTo("p1")
        assertThat(book.stacks()).isEmpty()
    }

    @Test
    fun `PRIMARY plus STACK same side averages entry`() {
        val book = LegBook("EURUSD")
        book.add(leg("p1", Side.BUY, "0.1", "1.10"))
        book.add(leg("s1", Side.BUY, "0.2", "1.12", role = LegRole.STACK, parentLegId = "p1"))
        assertThat(book.netQuantity()).isEqualByComparingTo("0.3")
        val view = book.netView()!!
        // weighted: (0.1 × 1.10 + 0.2 × 1.12) / 0.3 = 0.334/0.3 = 1.11333...
        assertThat(view.avgEntryPrice).isCloseTo(BigDecimal("1.11333333"), org.assertj.core.data.Offset.offset(BigDecimal("0.00001")))
        assertThat(book.stacks()).hasSize(1)
        assertThat(book.stacks()[0].legId).isEqualTo("s1")
    }

    @Test
    fun `PRIMARY closes but STACK survives`() {
        val book = LegBook("EURUSD")
        book.add(leg("p1", Side.BUY, "0.1", "1.10"))
        book.add(leg("s1", Side.BUY, "0.2", "1.12", role = LegRole.STACK, parentLegId = "p1"))
        val closed = book.close("p1")
        assertThat(closed?.legId).isEqualTo("p1")
        assertThat(book.primary()).isNull()
        assertThat(book.stacks()).hasSize(1)
        assertThat(book.netQuantity()).isEqualByComparingTo("0.2")
        val view = book.netView()!!
        assertThat(view.avgEntryPrice).isEqualByComparingTo("1.12")
    }

    @Test
    fun `cannot add a second PRIMARY before closing the first`() {
        val book = LegBook("EURUSD")
        book.add(leg("p1", Side.BUY, "0.1", "1.10"))
        assertThatThrownBy { book.add(leg("p2", Side.BUY, "0.1", "1.15")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PRIMARY leg")
    }

    @Test
    fun `cannot add a leg for a different symbol`() {
        val book = LegBook("EURUSD")
        val mismatched =
            PositionLeg(
                legId = "x",
                symbol = "GBPUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entryPrice = BigDecimal("1.30"),
                openedAt = 0L,
                role = LegRole.PRIMARY,
            )
        assertThatThrownBy { book.add(mismatched) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("EURUSD")
    }

    @Test
    fun `opposite-direction legs net out`() {
        val book = LegBook("EURUSD")
        book.add(leg("p1", Side.BUY, "0.2", "1.10"))
        book.add(leg("s1", Side.SELL, "0.1", "1.20", role = LegRole.STACK, parentLegId = "p1"))
        assertThat(book.netQuantity()).isEqualByComparingTo("0.1")
        // netView keeps BUY-side legs for avg (the SELL leg's contribution is already in netQty)
        val view = book.netView()!!
        assertThat(view.avgEntryPrice).isEqualByComparingTo("1.10")
    }

    @Test
    fun `equal-and-opposite legs return zero-quantity view`() {
        val book = LegBook("EURUSD")
        book.add(leg("p1", Side.BUY, "0.1", "1.10"))
        book.add(leg("s1", Side.SELL, "0.1", "1.15", role = LegRole.STACK, parentLegId = "p1"))
        assertThat(book.netQuantity()).isEqualByComparingTo("0")
        val view = book.netView()!!
        assertThat(view.quantity).isEqualByComparingTo("0")
    }

    @Test
    fun `STACK leg requires parentLegId`() {
        assertThatThrownBy {
            PositionLeg(
                legId = "s-orphan",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entryPrice = BigDecimal("1.10"),
                openedAt = 0L,
                role = LegRole.STACK,
                parentLegId = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parentLegId")
    }
}
