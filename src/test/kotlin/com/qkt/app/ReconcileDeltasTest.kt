package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconcileDeltasTest {
    private fun ticket(
        id: String,
        symbol: String,
        side: Side,
        qty: String,
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
    )

    private fun engine(vararg pairs: Pair<String, String>): Map<String, Position> =
        pairs.associate { (symbol, qty) ->
            symbol to Position(symbol = symbol, quantity = BigDecimal(qty), avgEntryPrice = BigDecimal.ONE)
        }

    private fun attribution(vararg pairs: Pair<String, String>) =
        TicketAttribution().apply { pairs.forEach { (t, s) -> record(t, s) } }

    @Test
    fun `a prefixed broker key and a bare engine key net to one symbol, not two phantom deltas`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = listOf(ticket("t1", "EXNESS:XAUUSD", Side.SELL, "0.13")),
                attribution = attribution("t1" to "hedge_straddle"),
                enginePositions = engine("XAUUSD" to "-0.13"),
            )
        assertThat(deltas).isEmpty()
    }

    @Test
    fun `hedging straddle legs net to the engine net position`() {
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
                enginePositions = engine("XAUUSD" to "-0.13"),
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
                enginePositions = engine("XAUUSD" to "0.10"),
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
                enginePositions = emptyMap(),
            )
        assertThat(deltas).hasSize(1)
        assertThat(deltas[0].symbol).isEqualTo("unattributed:XAUUSD")
        assertThat(deltas[0].engineQty).isEqualByComparingTo("0")
        assertThat(deltas[0].brokerQty).isEqualByComparingTo("-0.20")
    }

    @Test
    fun `a genuine net mismatch for the owner is reported`() {
        val deltas =
            reconcileDeltas(
                ownerId = "hedge_straddle",
                brokerTickets = listOf(ticket("t", "EXNESS:XAUUSD", Side.SELL, "0.13")),
                attribution = attribution("t" to "hedge_straddle"),
                enginePositions = engine("XAUUSD" to "0.25"),
            )
        assertThat(deltas).hasSize(1)
        assertThat(deltas[0].symbol).isEqualTo("XAUUSD")
        assertThat(deltas[0].engineQty).isEqualByComparingTo("0.25")
        assertThat(deltas[0].brokerQty).isEqualByComparingTo("-0.13")
    }
}
