package com.qkt.app

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt restarts inside the window between a bracket entry's partial fill and the venue cancelling its
 * remainder (#1329): the next session, on the same state and the gateway's account, books the order
 * as cancelled with its filled part, holds exactly the fills, arms the stop and target for that part,
 * counts no open entry, and sends nothing again.
 */
class LiveGatewayPartFillRestartTest {
    private val f =
        LiveGatewayRestartFixture { symbol ->
            val quantity = BigDecimal("0.5")
            val entry = OrderRequest.Market("e1", symbol, Side.BUY, quantity, TimeInForce.GTC, 5L, "guard")
            val stop = StopLossSpec.Fixed(BigDecimal("69000"))
            OrderRequest.Bracket(
                "b1",
                symbol,
                Side.BUY,
                quantity,
                entry,
                BigDecimal("71000"),
                stop,
                TimeInForce.GTC,
                5L,
                "guard",
            )
        }

    @AfterEach
    fun close() = f.close()

    private fun exits() = f.fake.submits.filter { it.side == "sell" }

    private fun assertRecovered(
        after: LiveGatewayRestartFixture.Running,
        state: Path,
    ) {
        f.await { exits().size == 2 }
        val seenAt = System.currentTimeMillis()
        f.await { after.strategy.openOrders == 0 && System.currentTimeMillis() - seenAt > 300 }
        assertThat(f.fake.submits.count { it.clientOrderId.startsWith("e1.") }).isEqualTo(1)
        assertThat(after.held()).isEqualByComparingTo("0.3")
        assertThat(
            exits().map {
                it.type to
                    BigDecimal(it.stopPrice ?: it.limitPrice).stripTrailingZeros().toPlainString()
            },
        ).containsExactlyInAnyOrder("stop" to "69000", "limit" to "71000")
        assertThat(exits().map { BigDecimal(it.quantity) }).allSatisfy { assertThat(it).isEqualByComparingTo("0.3") }
        assertThat(after.strategy.entered).isFalse()
        assertThat(FileStatePersistor(state).loadPendingOrders("guard").keys).doesNotContain("e1")
    }

    @Test
    fun `a remainder cancelled while qkt was down, after a fill it booked, ends with the filled part protected`(
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
    fun `a fill and the remainder's cancel both made while qkt was down end with the filled part protected`(
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
