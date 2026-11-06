package com.qkt.trade.session

import com.qkt.app.LiveSessionHandle
import com.qkt.common.Clock
import com.qkt.execution.Trade
import com.qkt.positions.Position
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveBotRunBackendTest {
    private val gold = "EXNESS:XAUUSD"

    private class Handle(
        val held: Map<String, List<Position>>,
    ) : LiveSessionHandle {
        override var running: Boolean = true
        override val droppedTicks: Long = 0L
        var positionReads = 0

        override fun stop() {
            running = false
        }

        override fun awaitTermination(timeout: Duration): Boolean = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() = Unit

        override fun positionsFor(strategyId: String): List<Position> {
            positionReads++
            return held[strategyId].orEmpty()
        }
    }

    /** Each read moves time on by a second, so a wait loop reaches its deadline without sleeping. */
    private class Ticking : Clock {
        var reads = 0

        override fun now(): Long = 1_000L * reads++
    }

    @Test
    fun `positions net every identity's holding per symbol`() {
        val handle =
            Handle(
                mapOf(
                    "a" to listOf(Position(gold, BigDecimal("0.30"), BigDecimal("2400"), 5L)),
                    "b" to listOf(Position(gold, BigDecimal("-0.10"), BigDecimal("2410"), 9L)),
                ),
            )

        val net = LiveBotRunBackend(handle, setOf("a", "b"), pollMs = 0L).positions()

        assertThat(net.keys).containsExactly(gold)
        assertThat(net.getValue(gold).quantity).isEqualByComparingTo("0.20")
        assertThat(net.getValue(gold).avgEntryPrice).isEqualByComparingTo("2400")
        assertThat(net.getValue(gold).openedAt).isEqualTo(5L)
    }

    @Test
    fun `awaiting flat gives up at the deadline while a position stays open`() {
        val handle = Handle(mapOf("a" to listOf(Position(gold, BigDecimal.ONE, BigDecimal("2400")))))
        val clock = Ticking()

        LiveBotRunBackend(handle, setOf("a"), clock = clock, pollMs = 0L).awaitFlat(timeoutMs = 3_000L) { false }

        // Deadline read at 0 s, then one more read per pass until the clock reads 3 s.
        assertThat(clock.reads).isEqualTo(4)
        assertThat(handle.positionReads).isEqualTo(3)
    }

    @Test
    fun `awaiting flat keeps waiting on pending orders even when flat`() {
        val handle = Handle(emptyMap())
        val clock = Ticking()
        var pendingChecks = 0

        LiveBotRunBackend(handle, setOf("a"), clock = clock, pollMs = 0L).awaitFlat(timeoutMs = 2_000L) {
            pendingChecks++
            true
        }

        assertThat(pendingChecks).isEqualTo(2)
    }

    @Test
    fun `awaiting flat returns at once when flat with nothing pending or the session stopped`() {
        val flat = Handle(emptyMap())
        val clock = Ticking()
        LiveBotRunBackend(flat, setOf("a"), clock = clock, pollMs = 0L).awaitFlat(timeoutMs = 60_000L) { false }
        assertThat(clock.reads).isEqualTo(1)

        val open = mapOf("a" to listOf(Position(gold, BigDecimal.ONE, BigDecimal("2400"))))
        val stopped = Handle(open).apply { running = false }
        LiveBotRunBackend(stopped, setOf("a"), clock = Ticking(), pollMs = 0L).awaitFlat(timeoutMs = 60_000L) { true }
        assertThat(stopped.positionReads).isZero()
    }

    @Test
    fun `live equity is venue truth so it is not modeled and finishing stops the session with no report`() {
        val handle = Handle(emptyMap())
        val backend = LiveBotRunBackend(handle, setOf("a"), clock = Ticking(), pollMs = 0L)

        assertThat(backend.equity()).isNull()
        assertThat(backend.nowMs()).isEqualTo(0L)
        assertThat(backend.finish()).isNull()
        assertThat(handle.running).isFalse()
    }
}
