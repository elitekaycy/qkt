package com.qkt.app

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StructureLegTest {
    private fun leg(side: Side = Side.BUY) = StructureLeg("DERIBIT:BTC-C", side, "o1", BigDecimal("1"), 0L)

    @Test
    fun `slices open at the average price and a final slice ends the open`() {
        val leg = leg()
        leg.open(BigDecimal("1"), BigDecimal("100"), final = false)
        assertThat(leg.openEnded).isFalse()

        leg.open(BigDecimal("3"), BigDecimal("200"))

        assertThat(leg.openEnded).isTrue()
        assertThat(leg.opened).isEqualByComparingTo("4")
        assertThat(leg.held).isEqualByComparingTo("4")
        assertThat(leg.entryPrice).isEqualByComparingTo("175")
    }

    @Test
    fun `a close never realizes more than is held, signed by the leg's side`() {
        val short = leg(Side.SELL)
        short.open(BigDecimal("2"), BigDecimal("50"))

        short.realize(BigDecimal("5"), BigDecimal("40"))

        assertThat(short.realized).isEqualByComparingTo("20")
        assertThat(short.held).isEqualByComparingTo("0")
    }

    @Test
    fun `a closing order works on for the rest of its size until its final slice`() {
        val leg = leg()
        leg.open(BigDecimal("3"), BigDecimal("10"))
        leg.beginClose("c1", BigDecimal("3"))
        assertThat(leg.unclosed).isEqualByComparingTo("0")

        leg.closeSlice("c1", BigDecimal("1"), final = false)
        assertThat(leg.closingOrders).containsEntry("c1", BigDecimal("2"))
        assertThat(leg.isClosing).isTrue()

        leg.closeSlice("c1", BigDecimal("2"), final = true)
        assertThat(leg.isClosing).isFalse()
    }

    @Test
    fun `a close before any open is refused`() {
        assertThatThrownBy { leg().realize(BigDecimal.ONE, BigDecimal("10")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("DERIBIT:BTC-C closed before it was opened")
    }
}
