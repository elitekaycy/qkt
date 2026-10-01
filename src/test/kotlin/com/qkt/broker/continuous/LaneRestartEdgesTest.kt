package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedStreamLane
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** What a restarted lane refuses to resume on, and how it treats a schedule that moved on while it was down. */
class LaneRestartEdgesTest {
    private val f = ScriptedLaneFixture()

    @Test
    fun `a lane saved on a contract its chain no longer lists refuses to restore`() {
        f.persistor.saveStreamLane("owner", f.saved().copy(contract = "BINANCE_UM:BTCUSDT_230929"))

        assertThatThrownBy { f.restart() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no longer lists")
    }

    @Test
    fun `a roll saved mid-flight that the chain now measures otherwise refuses to restore`() {
        f.holdIntoRoll()
        val saved = f.saved()
        f.persistor.saveStreamLane("owner", saved.copy(roll = saved.roll!!.copy(toPrice = BigDecimal("63900"))))

        assertThatThrownBy { f.restart() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("measured now")
    }

    @Test
    fun `a stream is one account its strategies share, so each strategy's persisted book stands at a restart`() {
        assertThat(f.broker.isAccountWide(f.front)).isTrue()
        assertThat(f.broker.isAccountWide(f.sep)).isFalse()
    }

    @Test
    fun `a roll still in flight after a long downtime ends before the stream rolls again`() {
        f.holdIntoRoll()
        val close = f.leg(":close")
        val g = f.restart(Instant.parse("2024-12-19T09:00:00Z").toEpochMilli())
        g.broker.watchBookedLegs { emptyList() }
        g.tick("64000")
        assertThat(g.venue.sent).isEmpty()

        g.last(close.id, f.sep, Side.SELL, "0.010", "63000")
        g.last(g.leg(":open").id, f.dec, Side.BUY, "0.010", "63800")
        g.tick("64000")

        val next = g.venue.sent.filter { it.id.startsWith("roll:") && it.id.endsWith(":close") }
        assertThat(next.map { it.symbol }).containsExactly(f.dec)
        assertThat(g.saved().roll!!.to).isEqualTo(f.mar)
    }

    private fun rest() =
        OrderRequest
            .Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            ).also { f.broker.submit(it) }

    private fun held(request: OrderRequest) =
        ManagedOrder(request.id, request, OrderState.WORKING, createdAt = 0L, lastUpdatedAt = 0L)

    @Test
    fun `an order that filled while its roll cancel was out is not booked again after a restart`() {
        rest()
        f.rollAt("63000")
        f.last("rest", f.sep, Side.BUY, "0.010", "62890")
        val g = f.restart()
        g.broker.watchBookedLegs { emptyList() }

        assertThat(g.venue.recovered.map { it.id }).doesNotContain("rest")
        assertThat(g.engine).isEmpty()
    }

    @Test
    fun `a restart saved between a roll's cancels and its first leg sends that leg once`() {
        f.broker.submit(
            OrderRequest.Market("entry", f.front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, f.clock.time, "s"),
        )
        f.last("entry", f.sep, Side.BUY, "0.010", "63010")
        rest()
        var atCancel: PersistedStreamLane? = null
        f.venue.onCancel = { if (atCancel == null) atCancel = f.saved() }
        f.rollAt("63000")
        val saved = requireNotNull(atCancel)
        assertThat(saved.roll!!.steps).isEmpty()

        val g = ScriptedLaneFixture(NoopStatePersistor().apply { saveStreamLane("owner", saved) }, f.clock.time)
        g.venue.knows = setOf("entry", "rest")
        g.broker.watchBookedLegs { emptyList() }

        assertThat(g.venue.sent.count { it.id.endsWith(":close") }).isEqualTo(1)
    }

    @Test
    fun `a re-placement the venue never received is sent again for the engine order it carries`() {
        val rest = rest()
        f.rollAt("63000")
        f.venue.onSubmit = { if (it.id == "rest~r1") error("down before the venue received it") }
        runCatching { f.cancelled("rest") }
        val g = f.restart()
        g.venue.knows = emptySet()

        assertThat(g.broker.recoverPendingOrders(listOf(held(rest)), emptySet())).containsExactly("rest")
        g.broker.watchBookedLegs { emptyList() }
        val again = g.venue.sent.single { it.id == "rest~r1" }
        assertThat(again.symbol to again.quantity).isEqualTo(f.dec to BigDecimal("0.010"))
    }

    @Test
    fun `a leg the venue answers while it takes the lane's orders back starts the next leg once, when ready`() {
        f.holdIntoRoll()
        val close = f.leg(":close")
        val g = f.restart()
        g.venue.knows = setOf(close.id)
        g.venue.onRecover = { g.last(close.id, f.sep, Side.SELL, "0.010", "63000") }
        g.broker.watchBookedLegs { emptyList() }

        assertThat(g.venue.sent.count { it.id.endsWith(":open") }).isEqualTo(1)
    }
}
