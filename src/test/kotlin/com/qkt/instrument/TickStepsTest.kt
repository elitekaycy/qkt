package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Deribit BTC_USDC options: tick 5 up to a premium of 1000, tick 20 above it. */
class TickStepsTest {
    private val steps = TickSteps(BigDecimal("5"), listOf(TickStep(BigDecimal("1000"), BigDecimal("20"))))

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `the tick depends on the price`() {
        assertThat(steps.tickAt(bd("995"))).isEqualByComparingTo("5")
        assertThat(steps.tickAt(bd("1000"))).isEqualByComparingTo("5")
        assertThat(steps.tickAt(bd("1005"))).isEqualByComparingTo("20")
    }

    @Test
    fun `levels snap down or up onto the grid that applies there`() {
        assertThat(steps.floor(bd("1013"))).isEqualByComparingTo("1000")
        assertThat(steps.ceil(bd("1013"))).isEqualByComparingTo("1020")
        assertThat(steps.floor(bd("997"))).isEqualByComparingTo("995")
        assertThat(steps.ceil(bd("997"))).isEqualByComparingTo("1000")
        assertThat(steps.ceil(bd("1020"))).isEqualByComparingTo("1020")
    }

    @Test
    fun `a level is on the grid only for its own tick`() {
        assertThat(steps.isOnGrid(bd("995"))).isTrue()
        assertThat(steps.isOnGrid(bd("1010"))).isFalse()
        assertThat(steps.isOnGrid(bd("1040"))).isTrue()
    }

    @Test
    fun `steps must rise and every tick must be positive`() {
        assertThatThrownBy { TickSteps(BigDecimal.ZERO, emptyList()) }.hasMessageContaining("tick")
        assertThatThrownBy {
            TickSteps(bd("5"), listOf(TickStep(bd("1000"), bd("20")), TickStep(bd("500"), bd("10"))))
        }.hasMessageContaining("ascending")
    }

    @Test
    fun `a step must start on its own grid so snapping never skips a valid level`() {
        assertThatThrownBy {
            TickSteps(
                bd("5"),
                listOf(TickStep(bd("1010"), bd("20"))),
            )
        }.hasMessageContaining("multiple")
    }
}
