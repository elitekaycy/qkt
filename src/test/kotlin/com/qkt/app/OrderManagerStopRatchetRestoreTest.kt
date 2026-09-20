package com.qkt.app

import com.qkt.app.OrderManagerStopRatchetFixtures.fixture
import com.qkt.app.OrderManagerStopRatchetFixtures.stepped
import com.qkt.app.OrderManagerStopRatchetFixtures.tick
import com.qkt.execution.OrderRequest
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedOcoLeg
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStopRatchetRestoreTest {
    @Test
    fun `restart resumes a stepped stop at its persisted cursor and level`() {
        val persistor = NoopStatePersistor()
        val before = fixture(persistor = persistor)
        before.manager.submit(stepped())
        before.tick("130", 1L)

        val saved = persistor.loadTrailingStops("alpha").single()
        assertThat(saved.stepIndex).isEqualTo(1)
        assertThat(saved.stopLevel).isEqualByComparingTo("100")
        persistor.saveOcoLegs(
            "alpha",
            listOf(
                PersistedOcoLeg(
                    clientOrderId = "step-sl",
                    brokerOrderId = "step-sl",
                    strategyId = "alpha",
                    request = stepped(),
                    siblingIds = listOf("step-tp"),
                ),
            ),
        )

        val after = fixture(persistor = persistor, closeTicket = "ticket-42")
        after.manager.restore(listOf("alpha"))
        assertThat(after.broker.recovered).isEmpty()
        after.tick("170", 2L)
        after.tick("139", 3L)

        val close = after.broker.submits.single() as OrderRequest.Market
        assertThat(close.closesTicket).isEqualTo("ticket-42")
    }
}
