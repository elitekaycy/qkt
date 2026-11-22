package com.qkt.broker.continuous

import com.qkt.app.OrderManager
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * qkt#1754: a rebase moves the engine's view of each working order with the lane's, so the engine, the lane and a
 * recovery all hold the order at the level that maps to the contract price the venue rests it at.
 */
class LaneSeriesRebaseEngineTest {
    private val f = ScriptedLaneFixture()
    private val engine = OrderManager(f.broker, f.bus, MarketPriceTracker(), f.clock)

    private val qty = BigDecimal("0.010")
    private val gtc = TimeInForce.GTC
    private val working =
        listOf(
            OrderRequest.Limit("tp", f.front, Side.SELL, qty, BigDecimal("63100"), gtc, 0, "s"),
            OrderRequest.Stop("sl", f.front, Side.SELL, qty, BigDecimal("62900"), gtc, 0, "s"),
            OrderRequest.StopLimit(
                "sll",
                f.front,
                Side.SELL,
                qty,
                BigDecimal("62800"),
                BigDecimal("62790"),
                gtc,
                0,
                "s",
            ),
        )

    /** [id]'s levels as the engine holds them. */
    private fun levels(id: String): List<BigDecimal> =
        when (val r = requireNotNull(engine.getOrder(id)).request) {
            is OrderRequest.Limit -> listOf(r.limitPrice)
            is OrderRequest.Stop -> listOf(r.stopPrice)
            is OrderRequest.StopLimit -> listOf(r.stopPrice, r.limitPrice)
            else -> emptyList()
        }

    @Test
    fun `a running session's rebase moves the engine's working orders by the shift, and not the venue's`() {
        f.broker.watchBookedLegs { emptyList() }
        engine.submit(
            OrderRequest.Market("entry", f.front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, f.clock.time, "s"),
        )
        f.last("entry", f.sep, Side.BUY, "0.010", "63000")
        working.forEach(engine::submit)
        val placed = f.venue.sent.filter { it.id != "entry" }
        val rebuilt =
            f.history.copy(
                rolls = f.history.rolls.map { if (it.from == "BTCUSDT_240628") it.copy(fromPrice = "65003") else it },
            )

        f.chains.useHistory(rebuilt.root, rebuilt)
        f.tick("63013")

        assertThat(levels("tp")).usingElementComparator(BigDecimal::compareTo).containsExactly(BigDecimal("63103"))
        assertThat(levels("sl")).usingElementComparator(BigDecimal::compareTo).containsExactly(BigDecimal("62903"))
        assertThat(levels("sll"))
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("62803"), BigDecimal("62793"))
        assertThat(placed).hasSize(3)
        assertThat(f.venue.sent.filter { it.id != "entry" }).isEqualTo(placed)
        assertThat(f.venue.cancels).isEmpty()
    }
}
