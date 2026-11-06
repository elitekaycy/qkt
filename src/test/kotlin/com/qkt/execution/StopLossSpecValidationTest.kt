package com.qkt.execution

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StopLossSpecValidationTest {
    private fun bd(value: String) = BigDecimal(value)

    @Test
    fun `a fixed stop needs a positive price`() {
        assertThatThrownBy { StopLossSpec.Fixed(bd("0")) }.hasMessage("Fixed stop price must be > 0: 0")
        assertThat(StopLossSpec.Fixed(bd("1")).price).isEqualByComparingTo("1")
    }

    @Test
    fun `an armed trail needs a positive distance and a non-negative threshold`() {
        assertThatThrownBy { StopLossSpec.ArmedTrail(bd("0"), bd("1")) }.hasMessage("trailDistance must be > 0: 0")
        assertThatThrownBy { StopLossSpec.ArmedTrail(bd("1"), bd("-1")) }.hasMessage("mfeThreshold must be >= 0: -1")
        assertThat(StopLossSpec.ArmedTrail(bd("1"), bd("0")).mfeThreshold).isEqualByComparingTo("0")
    }

    @Test
    fun `a step needs non-negative threshold and distance`() {
        assertThatThrownBy { StopLossSpec.Step(bd("-1"), bd("0")) }.hasMessage("mfeThreshold must be >= 0: -1")
        assertThatThrownBy { StopLossSpec.Step(bd("0"), bd("-2")) }.hasMessage("profitDistance must be >= 0: -2")
    }

    @Test
    fun `a stepped stop needs a positive distance and strictly increasing steps`() {
        val first = StopLossSpec.Step(bd("1"), bd("0"))
        val second = StopLossSpec.Step(bd("2"), bd("1"))

        assertThatThrownBy { StopLossSpec.SteppedStop(bd("0"), listOf(first)) }
            .hasMessage("initialDistance must be > 0: 0")
        assertThatThrownBy { StopLossSpec.SteppedStop(bd("1"), emptyList()) }
            .hasMessage("stepped stop must contain at least one step")
        assertThatThrownBy { StopLossSpec.SteppedStop(bd("1"), listOf(second, first)) }
            .hasMessage("step MFE thresholds must be strictly increasing")
        assertThat(StopLossSpec.SteppedStop(bd("1"), listOf(first, second)).steps).containsExactly(first, second)
    }

    @Test
    fun `a tightening stop refuses each non-positive part and a floor above its start`() {
        assertThatThrownBy { StopLossSpec.TimeTighten(bd("0"), bd("1"), 1L, bd("1")) }
            .hasMessage("initialDistance must be > 0: 0")
        assertThatThrownBy { StopLossSpec.TimeTighten(bd("2"), bd("0"), 1L, bd("1")) }
            .hasMessage("tightenBy must be > 0: 0")
        assertThatThrownBy { StopLossSpec.TimeTighten(bd("2"), bd("1"), 0L, bd("1")) }
            .hasMessage("intervalMs must be > 0: 0")
        assertThatThrownBy { StopLossSpec.TimeTighten(bd("2"), bd("1"), 1L, bd("0")) }
            .hasMessage("floorDistance must be > 0: 0")
        assertThatThrownBy { StopLossSpec.TimeTighten(bd("2"), bd("1"), 1L, bd("3")) }
            .hasMessage("floorDistance must be <= initialDistance: 3 > 2")
        assertThat(StopLossSpec.TimeTighten(bd("2"), bd("1"), 1L, bd("2")).floorDistance).isEqualByComparingTo("2")
    }
}
