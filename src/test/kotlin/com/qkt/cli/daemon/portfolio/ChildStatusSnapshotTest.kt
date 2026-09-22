package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSessionHandle
import com.qkt.app.OrderManager
import com.qkt.execution.Trade
import com.qkt.persistence.PersistedStrategyHalt
import com.qkt.positions.Position
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChildStatusSnapshotTest {
    private fun session(halts: List<PersistedStrategyHalt> = emptyList()) =
        object : LiveSessionHandle {
            override val running = true
            override val droppedTicks = 3L

            override fun stop() = Unit

            override fun awaitTermination(timeout: Duration) = true

            override fun recentTrades(): List<Trade> = emptyList()

            override fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo> = emptyList()

            override fun flatten() = Unit

            override fun positionsFor(strategyId: String): List<Position> =
                if (strategyId == "book:s1") {
                    listOf(Position("PROP:XAUUSD", BigDecimal("0.01"), BigDecimal("4368.11")))
                } else {
                    emptyList()
                }

            override fun staleSymbols() = mapOf("PROP:XAUUSD" to 9_000L)

            override fun strategyHalts() = halts
        }

    private fun snapshotOf(session: LiveSessionHandle) =
        childStatusSnapshot("book_366/s1", 1, "book:s1", 0L, Instant.EPOCH, session)

    @Test
    fun `a child reports the positions its strategy id holds under its display name`() {
        val snapshot = snapshotOf(session())

        assertThat(snapshot.strategy).isEqualTo("book_366/s1")
        assertThat(snapshot.positions.map { it.symbol to it.qty })
            .containsExactly("PROP:XAUUSD" to BigDecimal("0.01"))
        assertThat(snapshot.staleSymbols).containsExactly("PROP:XAUUSD")
        assertThat(snapshot.droppedTicks).isEqualTo(3L)
        assertThat(snapshot.halted).isFalse()
    }

    @Test
    fun `a child halted by risk control reports its halt`() {
        val halt = PersistedStrategyHalt("book:s1", "loss streak 3", "PERSISTENT", 20_000L, 1_000L)

        val snapshot = snapshotOf(session(listOf(halt)))

        assertThat(snapshot.halted).isTrue()
        assertThat(snapshot.haltReason).isEqualTo("loss streak 3")
        assertThat(snapshot.haltPersistent).isTrue()
    }
}
