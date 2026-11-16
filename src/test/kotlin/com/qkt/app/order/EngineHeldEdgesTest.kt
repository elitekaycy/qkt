package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.persistence.PersistedTrailingStop
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Edges of the engine-held order helpers that the order manager's own flows never reach. */
class EngineHeldEdgesTest {
    private val clock = FixedClock(0L)
    private val cancels = mutableListOf<String>()
    private val broker =
        object : Broker {
            override val name = "recording"
            override val capabilities = emptySet<OrderTypeCapability>()

            override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

            override fun cancel(orderId: String) {
                cancels += orderId
            }
        }
    private val book = OrderBook({ false }, {})

    private fun limit(id: String) =
        OrderRequest.Limit(id, "X", Side.BUY, Money.of("1"), Money.of("100"), TimeInForce.GTC, 0L, "alpha")

    @Test
    fun `a dynamic stop state that belongs to another order is refused on restore`() {
        val restore = EngineHeldRestore(book, ManagedStopBook(), PendingExposureBook(book), broker, clock)
        val request = limit("a")
        val foreign = PersistedTrailingStop("b", null, "alpha", request, armed = false, hwm = Money.of("0"))

        assertThatThrownBy { restore.restore("a", null, request, foreign, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("dynamic state b does not belong to a")
    }

    @Test
    fun `an order that is not a stop never reads as triggered`() {
        val managed = ManagedOrder("a", limit("a"), OrderState.PENDING, createdAt = 0L, lastUpdatedAt = 0L)

        assertThat(isTriggered(managed, Tick("X", Money.of("1"), 0L), ManagedStopBook())).isFalse()
    }

    @Test
    fun `a tracked cancel whose order left the book is dropped without a resend`() {
        val cancellations = HaltCancellations(book, broker, clock, { _, _ -> })
        cancellations.begin("gone")

        cancellations.retry(Long.MAX_VALUE)
        cancellations.retry(Long.MAX_VALUE)

        assertThat(cancels).isEmpty()
    }

    @Test
    fun `a restored order that is not an engine-held kind is refused`() {
        assertThatThrownBy { ManagedStopBook().restore("a", limit("a"), null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("unsupported restored engine-held order Limit")
    }

    @Test
    fun `an order that is not a moving stop has no trail level`() {
        val managed = ManagedOrder("a", limit("a"), OrderState.PENDING, createdAt = 0L, lastUpdatedAt = 0L)

        assertThat(ManagedStopBook().trailLevel(managed)).isNull()
    }
}
