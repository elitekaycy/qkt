package com.qkt.risk.rules

import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.MarginLevelProvider
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** On a multi-venue strategy an entry answers to the margin of the account it is routed to. */
class MarginFloorRoutingTest {
    private open class Venue : Broker {
        override val name = "venue"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = error("not used")

        override fun cancel(orderId: String) = error("not used")

        override fun modify(
            orderId: String,
            changes: com.qkt.broker.OrderModification,
        ) = error("not used")
    }

    private class MarginVenue(
        val level: String?,
    ) : Venue(),
        MarginLevelProvider {
        override val supportsMarginLevel = true

        override fun marginLevel(): BigDecimal? = level?.let(::BigDecimal)
    }

    private val positions = StrategyPositionTracker().account

    private fun composite(
        a: Broker,
        b: Broker,
    ) = CompositeBroker(
        listOf(
            SymbolPattern.exactSet(setOf("A:X")) to a,
            SymbolPattern.exactSet(setOf("B:X")) to b,
        ),
    )

    private fun entry(symbol: String) =
        OrderRequest.Market(
            id = "m1",
            symbol = symbol,
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private fun MarginFloor.judge(symbol: String) = evaluate(entry(symbol), positions)

    @Test
    fun `with two margin venues the floor still binds, each entry on its own venue's level`() {
        val floor = MarginFloor(composite(MarginVenue("150"), MarginVenue("850")), BigDecimal("200"))

        assertThat(floor.judge("A:X")).isInstanceOf(Decision.Reject::class.java)
        assertThat(floor.judge("B:X")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `an entry on a venue with no margin level is not judged by another venue's`() {
        val floor = MarginFloor(composite(MarginVenue("150"), Venue()), BigDecimal("200"))

        assertThat(floor.judge("A:X")).isInstanceOf(Decision.Reject::class.java)
        assertThat(floor.judge("B:X")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a venue whose level cannot be read blocks entries however healthy the other venue reads`() {
        val floor = MarginFloor(composite(MarginVenue(null), MarginVenue("850")), BigDecimal("200"))

        repeat(2) {
            assertThat(floor.judge("A:X")).isEqualTo(Decision.Approve)
            assertThat(floor.judge("B:X")).isEqualTo(Decision.Approve)
        }

        assertThat(floor.judge("A:X")).isInstanceOf(Decision.Reject::class.java)
        assertThat(floor.judge("B:X")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a single venue behind the composite refuses as it always did`() {
        val venue = CompositeBroker(listOf(SymbolPattern.exactSet(setOf("A:X")) to MarginVenue("150")))

        val decision = MarginFloor(venue, BigDecimal("200")).judge("A:X") as Decision.Reject

        assertThat(decision.reason).isEqualTo(
            "margin level 150% below floor 200% — no new exposure until headroom recovers (stop-out is the alternative)",
        )
    }
}
