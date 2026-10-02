package com.qkt.derivatives.futures

import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdjustmentChainTest {
    private val rolls =
        listOf(
            RollPrices(BigDecimal("60000"), BigDecimal("60800")),
            RollPrices(BigDecimal("90000"), BigDecimal("91500")),
        )

    @Test
    fun `panama offsets accumulate forward and keep the series continuous`() {
        val chain = AdjustmentChain(PriceAdjustment.PANAMA, rolls)
        assertThat(chain.shiftFor(0)).isEqualByComparingTo("0")
        assertThat(chain.shiftFor(1)).isEqualByComparingTo("-800")
        assertThat(chain.shiftFor(2)).isEqualByComparingTo("-2300")
        assertThat(
            chain.toContinuous(0, BigDecimal("60000")),
        ).isEqualByComparingTo(chain.toContinuous(1, BigDecimal("60800")))
        assertThat(
            chain.toContinuous(1, BigDecimal("90000")),
        ).isEqualByComparingTo(chain.toContinuous(2, BigDecimal("91500")))
    }

    @Test
    fun `ratio factors accumulate forward and keep the series continuous`() {
        val chain = AdjustmentChain(PriceAdjustment.RATIO, rolls)
        assertThat(chain.shiftFor(0)).isEqualByComparingTo("1")
        val a = chain.toContinuous(0, BigDecimal("60000"))
        val b = chain.toContinuous(1, BigDecimal("60800"))
        assertThat(a.subtract(b).abs()).isLessThan(BigDecimal("1e-8"))
    }

    @Test
    fun `none leaves prices raw`() {
        val chain = AdjustmentChain(PriceAdjustment.NONE, rolls)
        assertThat(chain.toContinuous(2, BigDecimal("91500"))).isEqualByComparingTo("91500")
    }

    @Test
    fun `history never changes when a roll is appended`() {
        val short = AdjustmentChain(PriceAdjustment.PANAMA, rolls.take(1))
        val long = AdjustmentChain(PriceAdjustment.PANAMA, rolls)
        assertThat(long.shiftFor(0)).isEqualByComparingTo(short.shiftFor(0))
        assertThat(long.shiftFor(1)).isEqualByComparingTo(short.shiftFor(1))
    }

    @Test
    fun `an index beyond the covered contracts is refused`() {
        assertThatThrownBy { AdjustmentChain(PriceAdjustment.PANAMA, rolls).shiftFor(3) }.hasMessageContaining("3")
    }

    @Test
    fun `non-positive reference prices are refused`() {
        assertThatThrownBy { RollPrices(BigDecimal.ZERO, BigDecimal.ONE) }.hasMessageContaining("fromPrice")
    }
}
