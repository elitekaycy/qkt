package com.qkt.risk

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.IntentBook
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.trackerFill
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskEnginePositionsTest {
    @Test
    fun `rules see the engine's open positions through its pending-aware view`() {
        val ledger = StrategyPositionTracker()
        IntentBook().apply(ledger, trackerFill("s", "c1", "XAUUSD", Side.BUY, "2", "100"))
        var seen: Map<String, Position> = emptyMap()
        val capture =
            object : RiskRule {
                override fun evaluate(
                    request: OrderRequest,
                    positions: PositionProvider,
                ): Decision {
                    seen = positions.allPositions()
                    return Decision.Approve
                }
            }
        val engine = RiskEngine(listOf(capture), ledger.account)

        val decision =
            engine.approve(OrderRequest.Market("o", "XAUUSD", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 0L, "s"))

        assertThat(decision).isEqualTo(Decision.Approve)
        assertThat(seen.getValue("XAUUSD").quantity).isEqualByComparingTo("2")
    }

    @Test
    fun `an empty group is approved without consulting any rule`() {
        val refuseAll =
            object : RiskRule {
                override fun evaluate(
                    request: OrderRequest,
                    positions: PositionProvider,
                ): Decision = Decision.Reject("never")
            }

        assertThat(RiskEngine(listOf(refuseAll), StrategyPositionTracker().account).approveGroup(emptyList()))
            .isEqualTo(Decision.Approve)
    }
}
