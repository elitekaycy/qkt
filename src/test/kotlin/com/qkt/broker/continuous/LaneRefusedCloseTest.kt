package com.qkt.broker.continuous

import com.qkt.events.BrokerEvent
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
}
