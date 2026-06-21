package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScaleQuantityTest {
    private fun mkt(qty: String) =
        OrderRequest.Market(
            id = "o",
            symbol = "X",
            side = Side.BUY,
            quantity = BigDecimal(qty),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    @Test
    fun `scales a market order quantity`() {
        val s = mkt("2").scaleQuantity(BigDecimal("0.5")) as OrderRequest.Market
        assertThat(s.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `recurses into composite legs`() {
        val oto =
            OrderRequest.OTO(
                id = "o",
                symbol = "X",
                side = Side.BUY,
                quantity = BigDecimal("2"),
                parent = mkt("2"),
                children = listOf(mkt("2")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val s = oto.scaleQuantity(BigDecimal("0.5")) as OrderRequest.OTO
        assertThat(s.quantity).isEqualByComparingTo("1")
        assertThat((s.parent as OrderRequest.Market).quantity).isEqualByComparingTo("1")
        assertThat((s.children[0] as OrderRequest.Market).quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `factor one returns the same instance`() {
        val m = mkt("2")
        assertThat(m.scaleQuantity(BigDecimal.ONE)).isSameAs(m)
    }
}
