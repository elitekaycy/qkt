package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A resting order pulled at a roll is re-placed only once the venue confirms its cancel, for what is left
 * of it then; whatever the venue does to it meanwhile (a slice, a fill, the engine's own cancel) counts.
 */
class RollCancelWindowTest {
    private val f = ScriptedLaneFixture()

    private fun rest() =
        f.broker.submit(
            OrderRequest.Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            ),
        )

    @Test
    fun `an order is re-placed only once its cancel is confirmed`() {
        rest()
        f.rollAt("63000")
        assertThat(f.venue.sent.none { it.id.startsWith("rest~") }).isTrue()

        f.cancelled("rest")

        val replaced = f.venue.sent.single { it.id == "rest~r1" }
        assertThat(replaced.symbol to replaced.quantity).isEqualTo(f.dec to BigDecimal("0.010"))
    }

    @Test
    fun `an order part-filled while its cancel is out is re-placed for what is left`() {
        val g = ScriptedLaneFixture()
        g.broker.submit(
            OrderRequest.Market("entry", g.front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, g.clock.time, "s"),
        )
        g.last("entry", g.sep, Side.BUY, "0.010", "63010")
        g.broker.submit(
            OrderRequest.Limit(
                "rest",
                g.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                g.clock.time,
                "s",
            ),
        )
        g.rollAt("63000")
        g.slice("rest", g.sep, Side.BUY, "0.004", "0.004", "62890")
        g.cancelled("rest")
        g.last(g.leg(":close").id, g.sep, Side.SELL, "0.010", "63000")
        g.last(g.leg(":open").id, g.dec, Side.BUY, "0.010", "63800")

        assertThat(
            g.venue.sent
                .single { it.id == "rest~r1" }
                .quantity,
        ).isEqualByComparingTo("0.006")
        assertThat(
            g
                .saved()
                .orders
                .single()
                .filled,
        ).isEqualByComparingTo("0.004")
    }

    @Test
    fun `an order that fills instead of cancelling is never placed again, and no cancel is awaited for it`() {
        rest()
        f.rollAt("63000")
        f.last("rest", f.sep, Side.BUY, "0.010", "62890")

        assertThat(
            f.engine
                .filterIsInstance<BrokerEvent.OrderFilled>()
                .single()
                .clientOrderId,
        ).isEqualTo("rest")
        assertThat(f.venue.sent.none { it.id.startsWith("rest~") }).isTrue()
        assertThat(f.saved().cancelling).isEmpty()
    }

    @Test
    fun `an order the engine cancels while its roll cancel is out is cancelled, not placed again`() {
        rest()
        f.rollAt("63000")
        f.broker.cancel("rest")
        f.cancelled("rest")

        assertThat(f.venue.sent.none { it.id.startsWith("rest~") }).isTrue()
        assertThat(
            f.engine.filterIsInstance<BrokerEvent.OrderCancelled>().map { it.clientOrderId },
        ).containsExactly("rest")
        assertThat(f.saved().orders).isEmpty()
    }
}
