package com.qkt.execution

import com.qkt.common.TradingCalendar
import com.qkt.positions.LegRole
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionValueRulesTest {
    @Test
    fun `a scale-out leg needs a positive target and a fraction in zero to one`() {
        assertThatThrownBy { ScaleOutLeg(BigDecimal.ZERO, BigDecimal("0.5")) }.hasMessage("priceTarget must be > 0: 0")
        assertThatThrownBy { ScaleOutLeg(BigDecimal.TEN, BigDecimal.ZERO) }.hasMessage("fraction must be in (0, 1]: 0")
        assertThatThrownBy { ScaleOutLeg(BigDecimal.TEN, BigDecimal("1.5")) }
            .hasMessage("fraction must be in (0, 1]: 1.5")
        assertThat(ScaleOutLeg(BigDecimal.TEN, BigDecimal.ONE).fraction).isEqualByComparingTo("1")
    }

    @Test
    fun `an open intent needs a leg id and a stack open needs its parent`() {
        assertThatThrownBy { LegIntent.Open(" ", LegRole.PRIMARY) }.hasMessage("Open.legId must not be blank")
        assertThatThrownBy { LegIntent.Open("s1", LegRole.STACK) }
            .hasMessage("STACK open intent must carry a parentLegId")
        assertThat(LegIntent.Open("s1", LegRole.STACK, parentLegId = "p1").parentLegId).isEqualTo("p1")
    }

    @Test
    fun `a close intent needs a leg id or a ticket`() {
        assertThatThrownBy { LegIntent.Close() }.hasMessage("Close intent needs a legId or a ticket")
        assertThat(LegIntent.Close(ticket = "T1").ticket).isEqualTo("T1")
    }

    @Test
    fun `a day order on a symbol that never trades ahead expires at the end of its current session`() {
        val closed =
            object : TradingCalendar by TradingCalendar.crypto() {
                override fun isInSession(
                    symbol: String,
                    t: Instant,
                ): Boolean = false

                override fun forSymbol(symbol: String): TradingCalendar = this
            }
        val now = Instant.parse("2026-10-26T14:00:00Z").toEpochMilli()

        assertThat(Instant.ofEpochMilli(dayExpiryMs(closed, "X", now)).toString()).isEqualTo("2026-10-27T00:00:00Z")
    }
}
