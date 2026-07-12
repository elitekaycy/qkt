package com.qkt.broker.mt5

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5RecoverySnapshotTest {
    @Test
    fun `retries until pending orders and positions come from one clean attempt`() {
        var pendingReads = 0
        var positionReads = 0
        val failures = mutableListOf<Int>()

        val snapshot =
            readMT5RecoverySnapshot(
                attempts = 3,
                backoffMs = 0L,
                onFailedAttempt = { attempt, _ -> failures += attempt },
                readPendingOrders = {
                    pendingReads++
                    if (pendingReads == 1) null else emptyList()
                },
                readPositions = {
                    positionReads++
                    emptyList()
                },
            )

        assertThat(snapshot.pendingOrders).isEmpty()
        assertThat(snapshot.positions).isEmpty()
        assertThat(pendingReads).isEqualTo(2)
        assertThat(positionReads).isEqualTo(2)
        assertThat(failures).containsExactly(1)
    }

    @Test
    fun `persistent read failure refuses recovery`() {
        val failures = mutableListOf<String>()

        assertThatThrownBy {
            readMT5RecoverySnapshot(
                attempts = 3,
                backoffMs = 0L,
                onFailedAttempt = { _, reason -> failures += reason },
                readPendingOrders = { null },
                readPositions = { emptyList() },
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("failed 3 times")
        assertThat(failures).hasSize(3)
        assertThat(failures).allMatch { it.contains("pending orders") }
    }
}
