package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PositionLegGuardsTest {
    private fun leg(
        role: LegRole,
        qty: String = "1",
        entry: String = "100",
    ) = PositionLeg("l", "X", Side.BUY, BigDecimal(qty), BigDecimal(entry), 1L, role)

    @Test
    fun `a leg needs a positive size and entry price`() {
        assertThatThrownBy { leg(LegRole.PRIMARY, qty = "0") }.hasMessage("PositionLeg.quantity must be > 0: 0")
        assertThatThrownBy { leg(LegRole.PRIMARY, entry = "-1") }.hasMessage("PositionLeg.entryPrice must be > 0: -1")
    }

    @Test
    fun `direct leg additions accept only their own role`() {
        val tracker = StrategyPositionTracker()

        assertThatThrownBy { tracker.addStackLeg("s", leg(LegRole.PRIMARY)) }
            .hasMessage("addStackLeg requires LegRole.STACK; got PRIMARY")
        assertThatThrownBy { tracker.addIndependentLeg("s", leg(LegRole.STACK)) }
            .hasMessage("addIndependentLeg requires LegRole.INDEPENDENT; got STACK")
        assertThat(tracker.allLegsFor("s")).isEmpty()
    }

    @Test
    fun `a stack leg added directly is booked beside the primary, not averaged into it`() {
        val tracker = StrategyPositionTracker()
        tracker.addStackLeg("s", leg(LegRole.STACK))

        assertThat(tracker.legById("s", "l")?.role).isEqualTo(LegRole.STACK)
        assertThat(tracker.closeLeg("s", "X", "l")?.legId).isEqualTo("l")
        assertThat(tracker.legBookFor("s", "X")).isNull()
    }
}
