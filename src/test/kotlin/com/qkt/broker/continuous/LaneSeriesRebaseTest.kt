package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.instrument.RollHistory
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * qkt#1754: a roll history rebuilt under a lane (`qkt fetch --rolls` re-pricing the June roll 3 points higher, so
 * September's shift grows from 0 to 3) is booked on the open position as a cost, whether a running session meets it at
 * its next look at the chain or a restart does. Working orders stay at the contract prices they rest at: their levels
 * move 3 points in the series, so a recovery or a fill maps them where the venue holds them.
 */
class LaneSeriesRebaseTest {
    private val f = ScriptedLaneFixture()

    /** The June roll priced 3 points higher, as a rebuild from other bars would. */
    private val rebuilt: RollHistory =
        f.history.copy(
            rolls =
                f.history.rolls.map {
                    if (it.from == "BTCUSDT_240628") it.copy(fromPrice = "65003") else it
                },
        )

    private val working =
        listOf(
            OrderRequest.Limit(
                "tp",
                f.front,
                Side.SELL,
                BigDecimal("0.010"),
                BigDecimal("63100"),
                TimeInForce.GTC,
                0,
                "s",
            ),
            OrderRequest.Stop(
                "sl",
                f.front,
                Side.SELL,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                0,
                "s",
            ),
            OrderRequest.StopLimit(
                "sll",
                f.front,
                Side.SELL,
                BigDecimal("0.010"),
                BigDecimal("62800"),
                BigDecimal("62790"),
                TimeInForce.GTC,
                0,
                "s",
            ),
            OrderRequest.Market("mkt", f.front, Side.SELL, BigDecimal("0.010"), TimeInForce.GTC, 0, "s"),
        )

    /** Long 0.010 on September at 63000 (shift 0), with a take-profit, two stops and a market order unanswered. */
    private fun holdWithOrders() {
        f.broker.watchBookedLegs { emptyList() }
        f.broker.submit(
            OrderRequest.Market("entry", f.front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, f.clock.time, "s"),
        )
        f.last("entry", f.sep, Side.BUY, "0.010", "63000")
        working.forEach(f.broker::submit)
    }

    private fun costs(fixture: ScriptedLaneFixture) =
        mutableListOf<CostIncurred>().also { list -> fixture.bus.subscribe<CostIncurred> { list += it } }

    private fun exitPrice(fixture: ScriptedLaneFixture): BigDecimal {
        fixture.last("tp", fixture.sep, Side.SELL, "0.010", "63100")
        return fixture.engine
            .filterIsInstance<BrokerEvent.OrderFilled>()
            .single { it.clientOrderId == "tp" }
            .price
    }

    private fun assertNetsTheContractLegs(
        exit: BigDecimal,
        booked: List<CostIncurred>,
    ) {
        assertThat(booked.single().amount).isEqualByComparingTo("0.030")
        assertThat(booked.single().reason).isEqualTo("series rebased ${f.sep} 0->3")
        // Continuous P&L less the move is the contract legs' P&L: (63100 - 63000) x 0.010.
        val continuous = exit.subtract(BigDecimal("63000")).multiply(BigDecimal("0.010"))
        assertThat(continuous.subtract(booked.single().amount)).isEqualByComparingTo("1.00")
    }

    @Test
    fun `a running session books a rebuilt history at its next look and leaves the venue's orders where they rest`() {
        holdWithOrders()
        val booked = costs(f)
        val sent = f.venue.sent.size

        f.chains.useHistory(rebuilt.root, rebuilt)
        f.tick("63013")

        assertThat(f.venue.sent).hasSize(sent)
        assertThat(f.venue.cancels).isEmpty()
        f.tick("63014")
        val exit = exitPrice(f)
        assertThat(exit).isEqualByComparingTo("63103")
        assertNetsTheContractLegs(exit, booked)
    }

    @Test
    fun `a restart on a rebuilt history recovers each order at its contract price and books the move once ready`() {
        holdWithOrders()
        val placed = f.venue.sent.filter { it.id in working.map(OrderRequest::id) }

        val g = f.restart(rolls = rebuilt)
        val booked = costs(g)
        val held = working.map { ManagedOrder(it.id, it, OrderState.WORKING, createdAt = 0L, lastUpdatedAt = 0L) }
        g.broker.recoverPendingOrders(held, emptySet())
        assertThat(booked).isEmpty()
        g.broker.watchBookedLegs { emptyList() }

        assertThat(g.venue.recovered.map { it.request }).containsExactlyInAnyOrderElementsOf(placed)
        assertThat(g.venue.sent).isEmpty()
        assertThat(g.venue.cancels).isEmpty()
        assertThat(g.saved().contractShift).isEqualByComparingTo("3")
        assertNetsTheContractLegs(exitPrice(g), booked)
        val again = f.restart(rolls = rebuilt)
        val none = costs(again)
        again.broker.watchBookedLegs { emptyList() }
        assertThat(none).isEmpty()
    }
}
