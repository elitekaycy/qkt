package com.qkt.broker.continuous

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A strategy stopped on a stream is never carried by a roll, by any path: what it holds stays on its contract until
 * settled. The bot2 soak's roll strategy, stopped by the refused roll of 2026-10-29, was carried by the next one on a
 * qkt that did not check: it bought a contract it did not hold at the ask of 240,517.50 and sold the one after.
 */
class LaneStoppedNeverRollsTest {
    private val f = ScriptedLaneFixture()

    private fun ScriptedLaneFixture.ready() {
        broker.recoverPendingOrders(emptyList(), emptySet())
        broker.watchBookedLegs { emptyList() }
    }

    private fun ScriptedLaneFixture.rollLegs() = venue.sent.filter { it.id.startsWith("roll:") }

    private fun ScriptedLaneFixture.assertStillStoppedOnSeptember() {
        val saved = saved()
        assertThat(saved.strategies.single().stopped).isNotNull()
        assertThat(saved.strategies.single().position).isEqualByComparingTo("0.010")
        assertThat(saved.holdings.single().contract).isEqualTo(sep)
        assertThat(saved.roll).isNull()
    }

    /** The lane the soak held: stopped on restoring a closing step whose leg had been refused the old way. */
    private fun stoppedAsOnTheSoak(): ScriptedLaneFixture {
        f.holdIntoRoll()
        f.persistor.saveStreamLane("owner", f.saved().copy(legs = emptyList()))
        return f.restart().also { it.ready() }
    }

    @Test
    fun `the next roll sends no leg for the lane the soak held, whatever the ask`() {
        val g = stoppedAsOnTheSoak()

        g.clock.time = g.nextRoll
        g.quote("64000", "63990", "240505")

        assertThat(g.rollLegs()).isEmpty()
        g.assertStillStoppedOnSeptember()
    }

    @Test
    fun `a second restart keeps the stop, and the next roll sends no leg`() {
        val g = stoppedAsOnTheSoak().restart()
        g.ready()
        g.assertStillStoppedOnSeptember()

        g.clock.time = g.nextRoll
        g.tick("64000")

        assertThat(g.rollLegs()).isEmpty()
        g.assertStillStoppedOnSeptember()
    }

    @Test
    fun `a restart that lands past the next roll sends no leg`() {
        val g = stoppedAsOnTheSoak().restart(f.nextRoll)
        g.ready()

        assertThat(g.rollLegs()).isEmpty()
        g.assertStillStoppedOnSeptember()
    }

    @Test
    fun `a roll saved in flight by an older qkt does not carry a holder that was already stopped`() {
        f.holdIntoRoll()
        val saved = f.saved()
        val roll = requireNotNull(saved.roll)
        f.persistor.saveStreamLane(
            "owner",
            saved.copy(
                strategies = saved.strategies.map { it.copy(stopped = "stopped by an earlier roll") },
                legs = emptyList(),
                roll = roll.copy(steps = emptyList()),
            ),
        )

        val g = f.restart()
        g.ready()

        assertThat(g.rollLegs()).isEmpty()
        assertThat(
            g
                .saved()
                .strategies
                .single()
                .stopped,
        ).isEqualTo("stopped by an earlier roll")
        g.assertStillStoppedOnSeptember()
    }
}
