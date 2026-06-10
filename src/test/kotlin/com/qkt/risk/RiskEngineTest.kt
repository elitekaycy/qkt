package com.qkt.risk

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionProvider
import com.qkt.positions.PositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskEngineTest {
    private val positions = PositionTracker()

    private fun order(
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: BigDecimal = Money.of("1"),
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = side,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = 1000L,
    )

    private fun approveAlways() =
        object : RiskRule {
            override fun evaluate(
                request: OrderRequest,
                positions: PositionProvider,
            ): Decision = Decision.Approve
        }

    private fun rejectAlways(reason: String) =
        object : RiskRule {
            override fun evaluate(
                request: OrderRequest,
                positions: PositionProvider,
            ): Decision = Decision.Reject(reason)
        }

    @Test
    fun `halt blocks new exposure but lets risk-reducing orders out`() {
        // Long 1 XAUUSD; the strategy halts. The way OUT must stay open (FIA 7.3):
        // a close-sized SELL passes, a new BUY and an over-sized SELL (which would
        // flip the position) are rejected.
        val tracker = PositionTracker()
        tracker.applyFill(
            com.qkt.events.BrokerEvent.OrderFilled(
                clientOrderId = "open-1",
                brokerOrderId = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = Money.of("2000"),
                quantity = Money.of("1"),
                strategyId = "s1",
                timestamp = 0L,
            ),
        )
        val riskState = RiskState.noOp()
        riskState.halt("daily loss")
        val engine = RiskEngine(emptyList(), emptyList(), tracker, riskState)

        assertThat(engine.approve(order(side = Side.BUY)))
            .isInstanceOf(Decision.Reject::class.java)
        assertThat(engine.approve(order(side = Side.SELL, qty = Money.of("1"))))
            .isEqualTo(Decision.Approve)
        assertThat(engine.approve(order(side = Side.SELL, qty = Money.of("2"))))
            .isInstanceOf(Decision.Reject::class.java)
        // Close-by-ticket is always risk-reducing.
        val closeByTicket =
            OrderRequest.Market(
                id = "close-1",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                closesTicket = "42",
            )
        assertThat(engine.approve(closeByTicket)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `empty rules list always approves`() {
        val engine = RiskEngine(rules = emptyList(), positions = positions)
        assertThat(engine.approve(order())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `single approving rule returns Approve`() {
        val engine = RiskEngine(rules = listOf(approveAlways()), positions = positions)
        assertThat(engine.approve(order())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `single rejecting rule returns Reject with that reason`() {
        val engine = RiskEngine(rules = listOf(rejectAlways("nope")), positions = positions)
        val decision = engine.approve(order())
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).isEqualTo("nope")
    }

    @Test
    fun `evaluates rules in order, first reject wins`() {
        val engine =
            RiskEngine(
                rules = listOf(approveAlways(), rejectAlways("first reject"), rejectAlways("second reject")),
                positions = positions,
            )
        val decision = engine.approve(order())
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).isEqualTo("first reject")
    }

    @Test
    fun `all rules approving returns Approve`() {
        val engine =
            RiskEngine(
                rules = listOf(approveAlways(), approveAlways(), approveAlways()),
                positions = positions,
            )
        assertThat(engine.approve(order())).isEqualTo(Decision.Approve)
    }
}
