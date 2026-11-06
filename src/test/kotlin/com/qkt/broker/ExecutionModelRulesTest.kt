package com.qkt.broker

import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionModelRulesTest {
    private val meta =
        InstrumentMeta(
            qktSymbol = "X",
            contractSize = BigDecimal.ONE,
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    @Test
    fun `execution models refuse negative or empty settings`() {
        assertThatThrownBy { RejectEveryNthOrder(0) }.hasMessage("RejectEveryNthOrder.every must be > 0: 0")
        assertThatThrownBy { FixedPointsSlippage(-1) }.hasMessage("FixedPointsSlippage.points must be >= 0: -1")
        assertThatThrownBy { UniformRandomSlippage(-1, 7L) }
            .hasMessage("UniformRandomSlippage.maxPoints must be >= 0: -1")
        assertThatThrownBy { SendLane(-1L, 0L) }.hasMessage("latencyMs must be >= 0: -1")
        assertThatThrownBy { SendLane(0L, -1L) }.hasMessage("orderSpacingMs must be >= 0: -1")
    }

    @Test
    fun `zero-point random slippage leaves the price alone`() {
        val price = BigDecimal("100.00")

        assertThat(UniformRandomSlippage(0, 7L).adjust(price, Side.BUY, meta)).isSameAs(price)
        assertThat(FixedPointsSlippage(0).adjust(price, Side.SELL, meta)).isEqualByComparingTo("100.00")
    }

    @Test
    fun `a partial fill whose first slice rounds to nothing fills whole`() {
        assertThat(FractionalPartialFill(BigDecimal("0.4")).slices(BigDecimal("0.02"), meta))
            .containsExactly(BigDecimal("0.02"))
        assertThat(FractionalPartialFill(BigDecimal("0.5")).slices(BigDecimal("0.04"), meta))
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("0.02"), BigDecimal("0.02"))
    }

    @Test
    fun `positions without an open time have no venue position id`() {
        val untimed = Position("X", BigDecimal.ONE, BigDecimal("100"))

        assertThat(VenueLeg.of(null, untimed)).isEqualTo(VenueLeg("IN", null))
        assertThat(VenueLeg.of(null, null)).isEqualTo(VenueLeg("IN", null))
        assertThat(VenueLeg.idOf(untimed)).isNull()
    }

    @Test
    fun `a predicted leg keeps the open time while the position continues and takes the fill time otherwise`() {
        val long = Position("X", BigDecimal("2"), BigDecimal("100"), openedAt = 10L)

        assertThat(VenueLeg.predict("X", long, Side.SELL, BigDecimal.ONE, 50L)).isEqualTo(VenueLeg("OUT", "X#10"))
        assertThat(VenueLeg.predict("X", long, Side.SELL, BigDecimal("3"), 50L))
            .isEqualTo(VenueLeg("INOUT", "X#10", "X#50"))
        assertThat(VenueLeg.predict("X", null, Side.BUY, BigDecimal.ONE, 60L)).isEqualTo(VenueLeg("IN", "X#60"))
    }
}
