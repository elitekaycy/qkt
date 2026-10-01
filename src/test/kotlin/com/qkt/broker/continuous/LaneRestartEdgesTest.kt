package com.qkt.broker.continuous

import com.qkt.common.Side
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
}
