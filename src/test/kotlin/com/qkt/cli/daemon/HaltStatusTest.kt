package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.persistence.PersistedStrategyHalt
import com.qkt.risk.HaltScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HaltStatusTest {
    private fun session(
        globalReason: String? = null,
        strategyHalts: List<PersistedStrategyHalt> = emptyList(),
    ): LiveSessionHandle =
        object : LiveSessionHandle {
            override val running = true
            override val droppedTicks = 0L

            override fun stop() = Unit

            override fun awaitTermination(timeout: java.time.Duration) = true

            override fun recentTrades() = emptyList<com.qkt.execution.Trade>()

            override fun pendingStackLayerInfos() = emptyList<com.qkt.app.OrderManager.PendingStackLayerInfo>()

            override fun flatten() = Unit

            override fun isHalted() = globalReason != null

            override fun haltReason() = globalReason

            override fun haltScope() = globalReason?.let { HaltScope.PERSISTENT }

            override fun strategyHalts() = strategyHalts
        }

    @Test
    fun `a running strategy reports no halt`() {
        assertThat(HaltStatus.of(session(), "alpha")).isEqualTo(HaltStatus.NONE)
    }

    @Test
    fun `an operator halt is reported with its reason and scope`() {
        val status = HaltStatus.of(session(globalReason = "operator"), "alpha")

        assertThat(status).isEqualTo(HaltStatus(halted = true, reason = "operator", scope = "PERSISTENT"))
    }

    @Test
    fun `a halt scoped to one strategy is reported for that strategy only`() {
        val halts =
            listOf(
                PersistedStrategyHalt("alpha", "LossStreakHalt[alpha]: 3 consecutive losses, max 3", "DAILY", 20_000L),
            )

        assertThat(HaltStatus.of(session(strategyHalts = halts), "alpha").reason).startsWith("LossStreakHalt")
        assertThat(HaltStatus.of(session(strategyHalts = halts), "beta")).isEqualTo(HaltStatus.NONE)
    }

    @Test
    fun `the snapshot carries the halt so status can show it`() {
        val snapshot =
            buildSnapshot(
                "alpha",
                1,
                0L,
                "2026-09-21T00:00:00Z",
                emptyList(),
                halt = HaltStatus(true, "operator", "PERSISTENT"),
            )

        assertThat(snapshot.halted).isTrue()
        assertThat(snapshot.haltReason).isEqualTo("operator")
        assertThat(snapshot.haltScope).isEqualTo("PERSISTENT")
    }

    @Test
    fun `a persistent halt says when it tripped and that only resume clears it`() {
        val halts =
            listOf(PersistedStrategyHalt("alpha", "operator", "PERSISTENT", 20_000L, haltedAtMs = 1_789_950_000_000L))

        val snapshot =
            buildSnapshot(
                "alpha",
                1,
                0L,
                "2026-09-21T00:00:00Z",
                emptyList(),
                halt = HaltStatus.of(session(strategyHalts = halts), "alpha"),
            )

        assertThat(snapshot.haltPersistent).isTrue()
        assertThat(snapshot.haltedAt).isEqualTo("2026-09-21T00:20:00Z")
    }

    @Test
    fun `a daily halt is not persistent, and one recorded before timestamps existed has no time`() {
        val halts = listOf(PersistedStrategyHalt("alpha", "DailyLoss", "DAILY", 20_000L))

        val snapshot =
            buildSnapshot(
                "alpha",
                1,
                0L,
                "2026-09-21T00:00:00Z",
                emptyList(),
                halt = HaltStatus.of(session(strategyHalts = halts), "alpha"),
            )

        assertThat(snapshot.halted).isTrue()
        assertThat(snapshot.haltPersistent).isFalse()
        assertThat(snapshot.haltedAt).isNull()
    }
}
