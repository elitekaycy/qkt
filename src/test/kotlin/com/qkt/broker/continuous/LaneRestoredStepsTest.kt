package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.persistence.PersistedCarryStep
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A roll saved in flight with a holder's carry already decided resumes past that holder at a restart. */
class LaneRestoredStepsTest {
    private val f = ScriptedLaneFixture()

    private fun ScriptedLaneFixture.ready() {
        broker.recoverPendingOrders(emptyList(), emptySet())
        broker.watchBookedLegs { emptyList() }
    }

    @Test
    fun `a holder saved as stopped is not carried, and the resumed roll ends with it stopped`() {
        f.holdIntoRoll()
        val saved = f.saved()
        val roll = requireNotNull(saved.roll)
        val stopped = PersistedCarryStep.Stopped("s", BigDecimal("0.010"), "halted for test", null)
        f.persistor.saveStreamLane(
            "owner",
            saved.copy(
                legs = emptyList(),
                roll = roll.copy(steps = listOf(stopped)),
            ),
        )
        val sentBefore = f.venue.sent.size

        val g = f.restart()
        g.ready()
        g.tick("63000")

        assertThat(g.venue.sent.filter { it.id.startsWith("roll:") }).isEmpty()
        val after = g.saved()
        assertThat(after.roll).isNull()
        assertThat(after.strategies.single().stopped).contains("(halted for test)")
        assertThat(after.holdings.single().contract).isEqualTo(g.sep)
        assertThat(sentBefore).isPositive()
    }

    @Test
    fun `a holder saved as carried is not carried again, and the resumed roll sends no legs for it`() {
        f.holdIntoRoll()
        val saved = f.saved()
        val roll = requireNotNull(saved.roll)
        val close =
            BrokerEvent.OrderFilled(
                "c-1",
                null,
                f.sep,
                Side.SELL,
                BigDecimal("63000"),
                BigDecimal("0.010"),
                "s",
            )
        val open = BrokerEvent.OrderFilled("o-1", null, f.dec, Side.BUY, BigDecimal("63100"), BigDecimal("0.010"), "s")
        val carried = PersistedCarryStep.Carried("s", BigDecimal("0.010"), close, open)
        f.persistor.saveStreamLane("owner", saved.copy(legs = emptyList(), roll = roll.copy(steps = listOf(carried))))

        val g = f.restart()
        g.ready()
        g.tick("63000")

        assertThat(g.venue.sent.filter { it.id.startsWith("roll:") }).isEmpty()
        assertThat(g.saved().roll).isNull()
    }
}
