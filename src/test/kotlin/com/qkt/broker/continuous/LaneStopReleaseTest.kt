package com.qkt.broker.continuous

import com.qkt.broker.CompositeBroker
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.persistence.PersistedStreamLane
import com.qkt.persistence.PersistedStreamOrder
import com.qkt.persistence.PersistedStreamStrategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An operator's resume clears a failed roll's stop only when nothing of the strategy is left on the stream. */
class LaneStopReleaseTest {
    private val f = ScriptedLaneFixture()

    private fun ScriptedLaneFixture.order(
        id: String,
        strategyId: String = "s",
    ) = OrderRequest.Market(id, front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, clock.time, strategyId)

    /** The stop a refused closing leg leaves, with the position still on the old contract. */
    private fun ScriptedLaneFixture.stopHolding() {
        holdIntoRoll()
        venueBus.publish(BrokerEvent.OrderRejected(leg(":close").id, null, "refused", "s", clock.time))
    }

    /** The soak's lane: stopped by a refused close, then flat once the old contract settled. */
    private fun ScriptedLaneFixture.stopFlat() {
        stopHolding()
        clock.time = nextRoll
        tick("64000")
        venueBus.publish(ContractSettled(sep, BigDecimal("63500"), timestamp = clock.time))
    }

    private fun ScriptedLaneFixture.stopped(strategyId: String = "s") =
        saved().strategies.single { it.strategyId == strategyId }.stopped

    private fun ScriptedLaneFixture.restartedOn(
        edit: (PersistedStreamLane) -> PersistedStreamLane,
    ): ScriptedLaneFixture {
        persistor.saveStreamLane("owner", edit(saved()))
        return restart()
    }

    @Test
    fun `a flat lane's stop clears, and the lane takes the strategy's next order`() {
        f.stopFlat()
        assertThat(f.broker.submit(f.order("refused")).accepted).isFalse()

        val release = f.broker.releaseStreamStops("s").single()

        assertThat(release.cleared).isTrue()
        assertThat(release.stream).isEqualTo(f.front)
        assertThat(f.stopped()).isNull()
        assertThat(f.broker.submit(f.order("again")).accepted).isTrue()
        assertThat(
            f.venue.sent
                .last()
                .id,
        ).isEqualTo("again")
    }

    @Test
    fun `a clear survives a restart`() {
        f.stopFlat()
        f.broker.releaseStreamStops("s")

        val g = f.restart()

        assertThat(g.stopped()).isNull()
        assertThat(g.broker.submit(g.order("again")).accepted).isTrue()
    }

    @Test
    fun `a restart alone never clears a stop`() {
        f.stopFlat()
        val stop = f.stopped()

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.broker.watchBookedLegs { emptyList() }

        assertThat(stop).isNotNull()
        assertThat(g.stopped()).isEqualTo(stop)
        assertThat(g.broker.submit(g.order("again")).rejectReason).isEqualTo(stop)
    }

    @Test
    fun `a position still on the old contract keeps the stop, and says so`() {
        f.stopHolding()

        val release = f.broker.releaseStreamStops("s").single()

        assertThat(release.cleared).isFalse()
        assertThat(release.stands).contains("position is 0.010", "holds 0.010 of ${f.sep}")
        assertThat(f.stopped()).isNotNull()
        assertThat(f.broker.submit(f.order("again")).accepted).isFalse()
    }

    @Test
    fun `a contract the book holds keeps the stop even when the strategy's position reads zero`() {
        f.stopHolding()
        val g =
            f.restartedOn { lane ->
                lane.copy(strategies = lane.strategies.map { it.copy(position = BigDecimal.ZERO) })
            }

        val release = g.broker.releaseStreamStops("s").single()

        assertThat(release.stands).isEqualTo("the venue holds 0.010 of ${f.sep} for it")
    }

    @Test
    fun `a position the book does not hold keeps the stop`() {
        f.stopHolding()
        val g = f.restartedOn { it.copy(holdings = emptyList()) }

        assertThat(
            g.broker
                .releaseStreamStops("s")
                .single()
                .stands,
        ).isEqualTo("its position is 0.010")
    }

    @Test
    fun `an order still working keeps the stop, and another strategy's does not`() {
        f.stopFlat()
        val working = { strategyId: String ->
            PersistedStreamOrder(
                f.order("rest", strategyId),
                "rest",
                f.mar,
                0,
                BigDecimal("0.010"),
                BigDecimal.ZERO,
                false,
            )
        }
        val mine = f.restartedOn { it.copy(orders = listOf(working("s"))) }

        assertThat(
            mine.broker
                .releaseStreamStops("s")
                .single()
                .stands,
        ).isEqualTo("order rest is still working")
        assertThat(mine.stopped()).isNotNull()

        val theirs = mine.restartedOn { it.copy(orders = listOf(working("t"))) }

        assertThat(
            theirs.broker
                .releaseStreamStops("s")
                .single()
                .cleared,
        ).isTrue()
    }

    @Test
    fun `a roll in progress keeps the stop, with its leg still out`() {
        f.holdIntoRoll()
        val g =
            f.restartedOn { lane ->
                lane.copy(strategies = lane.strategies.map { it.copy(stopped = "stopped") })
            }

        val stands =
            g.broker
                .releaseStreamStops("s")
                .single()
                .stands

        assertThat(stands).contains("a roll is in progress", "roll leg ${f.leg(":close").id} is still out")
    }

    @Test
    fun `a release touches only that strategy, and a strategy with no stop has nothing to release`() {
        f.stopFlat()
        val g =
            f.restartedOn { lane ->
                lane.copy(strategies = lane.strategies + PersistedStreamStrategy("t", BigDecimal.ZERO, "stopped"))
            }

        assertThat(g.broker.releaseStreamStops("nobody")).isEmpty()
        g.broker.releaseStreamStops("s")

        assertThat(g.stopped("s")).isNull()
        assertThat(g.stopped("t")).isEqualTo("stopped")
    }

    @Test
    fun `a composite reaches the stream's lane past its other brokers`() {
        f.stopFlat()
        val composite =
            CompositeBroker(
                listOf(
                    SymbolPattern.exactSet(setOf("OTHER:X")) to ScriptedVenue(),
                    SymbolPattern.exactSet(setOf(f.front)) to f.broker,
                ),
            )

        assertThat(composite.releaseStreamStops("s").single().cleared).isTrue()
        assertThat(ScriptedVenue().releaseStreamStops("s")).isEmpty()
    }
}
