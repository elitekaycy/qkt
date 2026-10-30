package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A roll's closing leg the venue refuses moves nothing: the position stays on the old contract and the strategy
 * stops on the stream, across a restart too. Failing the session instead (as qkt did) left a carry no restart
 * could resume: the bot2 soak's roll strategy could not be deployed again after its 2026-10-29 roll.
 */
class LaneRefusedCloseTest {
    private val f = ScriptedLaneFixture()

    private fun ScriptedLaneFixture.refused(id: String) =
        venueBus.publish(BrokerEvent.OrderRejected(id, null, "client_order_id longer than 64", "s", clock.time))

    @Test
    fun `a refused closing leg stops the strategy on the stream and leaves its position on the old contract`() {
        f.holdIntoRoll()
        val close = f.leg(":close")
        f.refused(close.id)

        val saved = f.saved()
        assertThat(saved.strategies.single().stopped).contains("client_order_id longer than 64")
        assertThat(saved.holdings.single().contract).isEqualTo(f.sep)
        assertThat(saved.holdings.single().quantity).isEqualByComparingTo("0.010")
        assertThat(saved.roll).isNull()
        assertThat(saved.legs).isEmpty()
        assertThat(f.venue.sent.filter { it.id.endsWith(":open") }).isEmpty()
        assertThat(f.ledger.entries).isEmpty()
        assertThat(f.engine.filter { (it as BrokerEvent.OrderEvent).clientOrderId.startsWith("roll:") }).isEmpty()
    }

    @Test
    fun `a carry saved closing with its leg already ended restores stopped instead of refusing to start`() {
        f.holdIntoRoll()
        val close = f.leg(":close")
        // What a qkt that failed the session on the refusal left: the step still closing, the leg no longer out.
        f.persistor.saveStreamLane("owner", f.saved().copy(legs = emptyList()))

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.broker.watchBookedLegs { emptyList() }

        val saved = g.saved()
        assertThat(saved.strategies.single().stopped).contains("refused before the restart (${close.id})")
        assertThat(saved.holdings.single().contract).isEqualTo(f.sep)
        assertThat(saved.holdings.single().quantity).isEqualByComparingTo("0.010")
        assertThat(saved.roll).isNull()
        assertThat(g.venue.sent).isEmpty()
    }

    private fun ScriptedLaneFixture.expiries() =
        engine.filterIsInstance<BrokerEvent.OrderFilled>().filter { it.exitReason == ExitReason.EXPIRY }

    @Test
    fun `the next roll sends no leg for a strategy stopped by a refused close, and settlement closes its position`() {
        f.holdIntoRoll()
        f.refused(f.leg(":close").id)
        val stopped = f.saved().strategies.single().stopped
        val sent = f.venue.sent.size

        f.clock.time = f.nextRoll
        f.tick("64000")

        assertThat(f.venue.sent).hasSize(sent)
        assertThat(f.saved().roll).isNull()
        assertThat(f.saved().strategies.single().stopped).isEqualTo(stopped)
        assertThat(f.saved().holdings.single().contract).isEqualTo(f.sep)

        f.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), timestamp = f.clock.time))

        val settled = f.expiries().single()
        assertThat(settled.side to settled.symbol).isEqualTo(Side.SELL to f.front)
        assertThat(settled.quantity).isEqualByComparingTo("0.010")
        assertThat(f.saved().strategies.single().position).isEqualByComparingTo("0")
        assertThat(f.saved().holdings).isEmpty()
    }

    @Test
    fun `the next roll sends no leg for a strategy stopped by an untradeable roll`() {
        f.broker.submit(
            OrderRequest.Market("entry", f.front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, f.clock.time, "s"),
        )
        f.last("entry", f.sep, Side.BUY, "0.010", "63000")
        f.clock.time = Instant.parse("2024-10-01T00:00:00Z").toEpochMilli()
        f.tick("63000")
        val stopped = f.saved().strategies.single().stopped
        assertThat(stopped).contains("expired")

        f.clock.time = f.nextRoll
        f.tick("64000")

        assertThat(f.venue.sent.filter { it.id.startsWith("roll:") }).isEmpty()
        assertThat(f.saved().strategies.single().stopped).isEqualTo(stopped)
    }

    @Test
    fun `a position the lane's book does not hold on the contract is not carried, and stops the strategy`() {
        f.broker.submit(
            OrderRequest.Market("entry", f.front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, f.clock.time, "s"),
        )
        f.last("entry", f.sep, Side.BUY, "0.010", "63000")
        f.persistor.saveStreamLane("owner", f.saved().copy(holdings = emptyList()))

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.broker.watchBookedLegs { emptyList() }
        g.rollAt("63000")

        assertThat(g.venue.sent.filter { it.id.startsWith("roll:") }).isEmpty()
        assertThat(g.saved().strategies.single().stopped).contains("holds 0")
    }
}
