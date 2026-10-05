package com.qkt.app

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt restarts between a scale-out basis's partial fill and the venue cancelling its remainder (#1336):
 * the next session holds exactly the fills, arms one take-profit per leg sized to the filled part,
 * counts no open entry, sends nothing again, and the first target closes its share once it trades.
 */
class LiveGatewayScaleOutRestartTest {
    private val f =
        LiveGatewayRestartFixture { symbol ->
            val quantity = BigDecimal("0.5")
            val basis = OrderRequest.Market("e1", symbol, Side.BUY, quantity, TimeInForce.GTC, 5L, "guard")
            val legs =
                listOf(
                    ScaleOutLeg(BigDecimal("71000"), BigDecimal("0.5")),
                    ScaleOutLeg(BigDecimal("72000"), BigDecimal("0.5")),
                )
            OrderRequest.ScaleOut("s1", symbol, Side.BUY, quantity, basis, legs, TimeInForce.GTC, 5L, "guard")
        }

    @AfterEach
    fun close() = f.close()

    private fun persistedExits(state: Path) =
        FileStatePersistor(state)
            .loadPendingOrders("guard")
            .filterKeys { it.startsWith("s1-leg-") }
            .values

    private fun assertRecovered(
        after: LiveGatewayRestartFixture.Running,
        state: Path,
    ) {
        f.await { persistedExits(state).size == 2 }
        val seenAt = System.currentTimeMillis()
        f.await { after.strategy.openOrders == 0 && System.currentTimeMillis() - seenAt > 300 }
        assertThat(after.held()).isEqualByComparingTo("0.3")
        assertThat(persistedExits(state).map { it.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo("0.15") }
        assertThat(FileStatePersistor(state).loadPendingOrders("guard").keys).doesNotContain("e1")
        assertThat(f.fake.submits.map { it.clientOrderId.substringBefore('.') }).containsExactly("e1")
        assertThat(after.strategy.entered).isFalse()

        f.price = "71000"
        f.await { f.fake.submits.any { it.side == "sell" } }
        val close = f.fake.submits.single { it.side == "sell" }
        assertThat(close.type).isEqualTo("market")
        assertThat(BigDecimal(close.quantity)).isEqualByComparingTo("0.15")
    }

    @Test
    fun `a basis remainder cancelled while qkt was down, after a fill it booked, arms the legs for the filled part`(
        @TempDir state: Path,
    ) {
        val before = f.start(state)
        f.await { f.fake.submits.isNotEmpty() }
        f.fake.act { fill(f.sent("e1"), "f1", "0.3", "70000", System.currentTimeMillis()) }
        f.await { before.held().compareTo(BigDecimal("0.3")) == 0 }
        before.stop()
        f.fake.act { cancel(f.sent("e1")) }

        val after = f.start(state)
        try {
            assertRecovered(after, state)
        } finally {
            after.stop()
        }
    }

    @Test
    fun `a basis fill and its remainder's cancel both made while qkt was down arm the legs for the filled part`(
        @TempDir state: Path,
    ) {
        val before = f.start(state)
        f.await { f.fake.submits.isNotEmpty() }
        before.stop()
        f.fake.act { cancelAfterFilling(f.sent("e1"), "f1", "0.3", "70000") }

        val after = f.start(state)
        try {
            assertRecovered(after, state)
        } finally {
            after.stop()
        }
    }
}
