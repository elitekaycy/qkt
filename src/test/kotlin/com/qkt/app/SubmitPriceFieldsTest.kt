package com.qkt.app

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubmitPriceFieldsTest {
    private val one = BigDecimal.ONE
    private val gtc = TimeInForce.GTC

    private fun n(value: String) = BigDecimal(value)

    @Test
    fun `touch orders show their trigger and only a limit they carry`() {
        val market = OrderRequest.IfTouched("t1", "X", Side.BUY, one, n("10"), TriggerType.MARKET, null, gtc, 0L)
        val limited =
            OrderRequest.IfTouched("t2", "X", Side.BUY, one, n("10"), TriggerType.LIMIT, n("10.5"), gtc, 0L)

        assertThat(submitPriceFields(market)).isEqualTo("triggerPrice=10")
        assertThat(submitPriceFields(limited)).isEqualTo("triggerPrice=10 limitPrice=10.5")
    }

    @Test
    fun `trailing stops show their trail and offset`() {
        val trail =
            OrderRequest.TrailingStopLimit("s", "X", Side.SELL, one, n("2"), TrailMode.ABSOLUTE, n("0.5"), gtc, 0L)

        assertThat(submitPriceFields(trail)).isEqualTo("trailAmount=2 mode=ABSOLUTE limitOffset=0.5")
    }

    @Test
    fun `engine managed stops show their entry and shape`() {
        val armed =
            OrderRequest.ArmedTrailingStop("a", "X", Side.SELL, one, n("100"), n("3"), n("5"), gtc, 0L)
        val stepped =
            OrderRequest.SteppedStop(
                "b",
                "X",
                Side.SELL,
                one,
                n("100"),
                n("4"),
                listOf(StopLossSpec.Step(n("2"), n("1")), StopLossSpec.Step(n("4"), n("2"))),
                gtc,
                0L,
            )
        val tightening =
            OrderRequest.TimeTighteningStop(
                "c",
                "X",
                Side.SELL,
                one,
                n("100"),
                n("6"),
                n("1"),
                60_000L,
                n("2"),
                gtc,
                0L,
            )

        assertThat(submitPriceFields(armed)).isEqualTo("entry=100 trail=3 mfe=5")
        assertThat(submitPriceFields(stepped)).isEqualTo("entry=100 initial=4 steps=2")
        assertThat(submitPriceFields(tightening)).isEqualTo("entry=100 initial=6 tighten=1 everyMs=60000 floor=2")
    }
}
