package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconcileDeltasTest {
    private fun ticket(
        id: String,
        symbol: String,
        side: Side,
        qty: String,
        stopLoss: String? = null,
        takeProfit: String? = null,
        requestedStopLoss: String? = null,
        requestedTakeProfit: String? = null,
    ) = BrokerPositionTicket(
        ticket = id,
        symbol = symbol,
        side = side,
        qty = BigDecimal(qty),
        entryPrice = BigDecimal.ONE,
        currentPrice = null,
        profit = null,
        swap = null,
        openedAt = null,
        comment = null,
        stopLoss = stopLoss?.let(::BigDecimal),
        takeProfit = takeProfit?.let(::BigDecimal),
        requestedStopLoss = requestedStopLoss?.let(::BigDecimal),
        requestedTakeProfit = requestedTakeProfit?.let(::BigDecimal),
    )

    private fun leg(
        id: String,
        symbol: String,
        side: Side,
        qty: String,
    ) = PositionLeg(id, symbol, side, BigDecimal(qty), BigDecimal.ONE, 0L, LegRole.INDEPENDENT)

    private fun netting(symbol: String = "XAUUSD") = mapOf(symbol to PositionAccountingMode.NETTING)

    private fun attribution(vararg pairs: Pair<String, String>) =
        TicketAttribution().apply { pairs.forEach { (t, s) -> record(t, s) } }

    @Test
    fun `a prefixed broker key and a bare engine key net to one symbol, not two phantom deltas`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = listOf(ticket("t1", "EXNESS:XAUUSD", Side.SELL, "0.13")),
                attribution = attribution("t1" to "hedge_straddle"),
                engineLegs = listOf(leg("e1", "XAUUSD", Side.SELL, "0.13")),
                accountingModes = netting(),
            )
        assertThat(deltas).isEmpty()
    }

    @Test
    fun `confirmed netting account compares signed net position`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets =
                    listOf(
                        ticket("a", "EXNESS:XAUUSD", Side.BUY, "0.25"),
                        ticket("b", "EXNESS:XAUUSD", Side.SELL, "0.24"),
                        ticket("c", "EXNESS:XAUUSD", Side.SELL, "0.14"),
                    ),
                attribution = attribution("a" to "hedge_straddle", "b" to "hedge_straddle", "c" to "hedge_straddle"),
                engineLegs = listOf(leg("e1", "XAUUSD", Side.SELL, "0.13")),
                accountingModes = netting(),
            )
        assertThat(deltas).isEmpty()
    }

    @Test
    fun `another strategy's legs on the shared account are not this strategy's drift`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets =
                    listOf(
                        ticket("mine", "EXNESS:XAUUSD", Side.BUY, "0.10"),
                        ticket("theirs", "EXNESS:XAUUSD", Side.SELL, "0.50"),
                    ),
                attribution = attribution("mine" to "hedge_straddle", "theirs" to "latch_stack"),
                engineLegs = listOf(leg("e1", "XAUUSD", Side.BUY, "0.10")),
            )
        assertThat(deltas).isEmpty()
    }

    @Test
    fun `a venue position owned by no live strategy surfaces as an unattributed orphan`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = listOf(ticket("orphan", "EXNESS:XAUUSD", Side.SELL, "0.20")),
                attribution = attribution(),
                engineLegs = emptyList(),
            )
        assertThat(deltas).hasSize(1)
        assertThat(deltas[0].symbol).isEqualTo("unattributed:XAUUSD")
        assertThat(deltas[0].engineQty).isEqualByComparingTo("0")
        assertThat(deltas[0].brokerQty).isEqualByComparingTo("0.20")
        assertThat(deltas[0].side).isEqualTo(Side.SELL)
    }

    @Test
    fun `a genuine net mismatch for the owner is reported`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = listOf(ticket("t", "EXNESS:XAUUSD", Side.SELL, "0.13")),
                attribution = attribution("t" to "hedge_straddle"),
                engineLegs = listOf(leg("e1", "XAUUSD", Side.BUY, "0.25")),
                accountingModes = netting(),
            )
        assertThat(deltas).hasSize(1)
        assertThat(deltas[0].symbol).isEqualTo("XAUUSD")
        assertThat(deltas[0].engineQty).isEqualByComparingTo("0.25")
        assertThat(deltas[0].brokerQty).isEqualByComparingTo("-0.13")
    }

    @Test
    fun `hedging engine straddle versus flat venue reports both directions`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = emptyList(),
                attribution = attribution(),
                engineLegs =
                    listOf(
                        leg("long", "XAUUSD", Side.BUY, "0.10"),
                        leg("short", "XAUUSD", Side.SELL, "0.10"),
                    ),
                accountingModes = mapOf("XAUUSD" to PositionAccountingMode.HEDGING),
            )

        assertThat(deltas.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(deltas.map { it.engineQty }).allMatch { it.compareTo(BigDecimal("0.10")) == 0 }
        assertThat(deltas.map { it.brokerQty }).allMatch { it.signum() == 0 }
        assertThat(ReconcileReport(deltas, BigDecimal.ZERO, null).clean).isFalse()
    }

    @Test
    fun `net-zero orphan pair reports both gross directions`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets =
                    listOf(
                        ticket("long", "EXNESS:XAUUSD", Side.BUY, "0.10"),
                        ticket("short", "EXNESS:XAUUSD", Side.SELL, "0.10"),
                    ),
                attribution = attribution(),
                engineLegs = emptyList(),
                accountingModes = mapOf("XAUUSD" to PositionAccountingMode.HEDGING),
            )

        assertThat(deltas).hasSize(2)
        assertThat(deltas.map { it.symbol }).containsOnly("unattributed:XAUUSD")
        assertThat(deltas.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(ReconcileReport(deltas, BigDecimal.ZERO, null).clean).isFalse()
    }

    @Test
    fun `a vanished requested stop is reported for its owning strategy`() {
        val deltas =
            reconcileProtectionDeltas(
                ownerId = "hedge_straddle",
                brokerTickets =
                    listOf(
                        ticket(
                            "mine",
                            "EXNESS:XAUUSD",
                            Side.BUY,
                            "0.10",
                            stopLoss = "0",
                            takeProfit = "110",
                            requestedStopLoss = "90",
                            requestedTakeProfit = "110",
                        ),
                    ),
                attribution = attribution("mine" to "hedge_straddle"),
            )

        assertThat(deltas).hasSize(1)
        assertThat(deltas.single().ticket).isEqualTo("mine")
        assertThat(deltas.single().requestedStopLoss).isEqualByComparingTo("90")
        assertThat(deltas.single().brokerStopLoss).isEqualByComparingTo("0")
    }

    @Test
    fun `an intentionally unprotected ticket is not protection drift`() {
        val deltas =
            reconcileProtectionDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = listOf(ticket("mine", "EXNESS:XAUUSD", Side.BUY, "0.10", stopLoss = "0")),
                attribution = attribution("mine" to "hedge_straddle"),
            )

        assertThat(deltas).isEmpty()
    }
}
