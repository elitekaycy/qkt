package com.qkt.derivatives.futures

import com.qkt.common.Side
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PriceSpaceTest {
    private val tick = BigDecimal("0.25")
    private val panama = PriceSpace(PriceAdjustment.PANAMA, BigDecimal("-10.1"), tick)
    private val ratio = PriceSpace(PriceAdjustment.RATIO, BigDecimal("0.5"), tick)

    @Test
    fun `panama maps levels by the offset and snaps without improving the order`() {
        // continuous 100 = raw + (-10.1) -> raw 110.1 -> buy limit floors to 110.00, sell limit ceils to 110.25
        assertThat(panama.limitToContract(BigDecimal("100"), Side.BUY)).isEqualByComparingTo("110.00")
        assertThat(panama.limitToContract(BigDecimal("100"), Side.SELL)).isEqualByComparingTo("110.25")
        assertThat(panama.stopToContract(BigDecimal("100"), Side.BUY)).isEqualByComparingTo("110.25")
        assertThat(panama.stopToContract(BigDecimal("100"), Side.SELL)).isEqualByComparingTo("110.00")
    }

    @Test
    fun `a level already on the grid is unchanged`() {
        val exact = PriceSpace(PriceAdjustment.PANAMA, BigDecimal("-10"), tick)
        assertThat(exact.limitToContract(BigDecimal("100.25"), Side.BUY)).isEqualByComparingTo("110.25")
        assertThat(exact.stopToContract(BigDecimal("100.25"), Side.SELL)).isEqualByComparingTo("110.25")
    }

    @Test
    fun `raw prices map into the continuous space`() {
        assertThat(panama.toContinuous(BigDecimal("110.1"))).isEqualByComparingTo("100")
        assertThat(ratio.toContinuous(BigDecimal("200"))).isEqualByComparingTo("100")
    }

    @Test
    fun `ratio maps levels by the factor and scales distances`() {
        assertThat(ratio.limitToContract(BigDecimal("100"), Side.BUY)).isEqualByComparingTo("200")
        assertThat(ratio.distanceToContract(BigDecimal("5"))).isEqualByComparingTo("10")
        assertThat(panama.distanceToContract(BigDecimal("5"))).isEqualByComparingTo("5")
    }

    @Test
    fun `a continuous price at or below zero is refused`() {
        val deep = PriceSpace(PriceAdjustment.PANAMA, BigDecimal("-200"), tick)
        assertThatThrownBy { deep.toContinuous(BigDecimal("150")) }.hasMessageContaining("ratio")
    }
}
