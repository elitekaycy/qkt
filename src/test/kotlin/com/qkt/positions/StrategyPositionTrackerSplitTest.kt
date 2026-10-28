package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A stock split restates the ledger and realizes nothing: NVDA's 10-for-1 turns one share at 600 into ten at 60, a
 * short the same, and a 1-for-8 reverse split eight shares at 10 into one at 80. Ids, roles and tickets stay put, the
 * account's net follows, and excursions are measured in the new price.
 */
class StrategyPositionTrackerSplitTest {
    private fun fill(
        strategyId: String,
        id: String,
        side: Side,
        qty: String,
        price: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = id,
        brokerOrderId = "T-$id",
        symbol = "EXNESS:NVDA",
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = 1L,
    )

    @Test
    fun `a ten-for-one split gives ten times the shares at a tenth of the price, long and short alike`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("long", "a", Side.BUY, "1", "600"))
        intents.apply(tracker, fill("short", "b", Side.SELL, "2", "600"))
        tracker.onTick("EXNESS:NVDA", BigDecimal("620")) // long MFE 20, short MAE 20
        val legBefore = tracker.allLegsFor("long").single()

        tracker.restateForSplit("EXNESS:NVDA", BigDecimal(10))

        val leg = tracker.allLegsFor("long").single()
        assertThat(leg.quantity).isEqualByComparingTo("10")
        assertThat(leg.entryPrice).isEqualByComparingTo("60")
        assertThat(leg.legId).isEqualTo(legBefore.legId)
        assertThat(leg.brokerTicket).isEqualTo(legBefore.brokerTicket)
        assertThat(leg.role).isEqualTo(legBefore.role)
        assertThat(tracker.positionFor("short", "EXNESS:NVDA")!!.quantity).isEqualByComparingTo("-20")
        assertThat(tracker.account.positionFor("EXNESS:NVDA")!!.quantity).isEqualByComparingTo("-10")
        assertThat(tracker.primaryMfeFor("long", "EXNESS:NVDA")).isEqualByComparingTo("2")
        assertThat(tracker.primaryMaeFor("short", "EXNESS:NVDA")).isEqualByComparingTo("2")
        // Measured in the new price from here: 62 is the old 620.
        tracker.onTick("EXNESS:NVDA", BigDecimal("63"))
        assertThat(tracker.primaryMfeFor("long", "EXNESS:NVDA")).isEqualByComparingTo("3")
    }

    @Test
    fun `a reverse split consolidates the shares, and a non-positive ratio is refused`() {
        val tracker = StrategyPositionTracker()
        IntentBook().apply(tracker, fill("long", "a", Side.BUY, "8", "10"))
        tracker.restateForSplit("EXNESS:NVDA", BigDecimal("0.125"))
        val leg = tracker.allLegsFor("long").single()
        assertThat(leg.quantity).isEqualByComparingTo("1")
        assertThat(leg.entryPrice).isEqualByComparingTo("80")
        assertThatThrownBy {
            tracker.restateForSplit(
                "EXNESS:NVDA",
                BigDecimal.ZERO,
            )
        }.hasMessageContaining("must be positive")
        // A symbol nobody holds is a no-op.
        tracker.restateForSplit("EXNESS:AAPL", BigDecimal(4))
        assertThat(tracker.allLegsFor("long").single().quantity).isEqualByComparingTo("1")
    }
}
