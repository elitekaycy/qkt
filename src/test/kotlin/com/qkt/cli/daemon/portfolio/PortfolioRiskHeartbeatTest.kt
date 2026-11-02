package com.qkt.cli.daemon.portfolio

import com.qkt.app.EngineSnapshotTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioRiskHeartbeatTest {
    private val halts = mutableListOf<String>()

    private fun heartbeat(evaluate: () -> Unit) = PortfolioRiskHeartbeat(evaluate) { halts += it }

    @Test
    fun `slow reads like the soak's 18 00 burst do not halt the book`() {
        val beat = heartbeat { throw EngineSnapshotTimeoutException() }

        repeat(PortfolioRiskHeartbeat.SLOW_READ_LIMIT - 1) { beat.beat() }

        assertThat(halts).isEmpty()
    }

    @Test
    fun `an engine that never answers does halt the book, and says it was slow`() {
        val beat = heartbeat { throw EngineSnapshotTimeoutException() }

        repeat(PortfolioRiskHeartbeat.SLOW_READ_LIMIT) { beat.beat() }

        assertThat(halts.single()).contains("unevaluated for 20 consecutive slow reads")
    }

    @Test
    fun `an answered read clears the slow count`() {
        var slow = true
        val beat = heartbeat { if (slow) throw EngineSnapshotTimeoutException() }

        repeat(PortfolioRiskHeartbeat.SLOW_READ_LIMIT - 1) { beat.beat() }
        slow = false
        beat.beat()
        slow = true
        repeat(PortfolioRiskHeartbeat.SLOW_READ_LIMIT - 1) { beat.beat() }

        assertThat(halts).isEmpty()
    }

    @Test
    fun `an evaluation that errors still fails closed on the third in a row`() {
        val beat = heartbeat { throw IllegalArgumentException("bad snapshot") }

        repeat(2) { beat.beat() }
        assertThat(halts).isEmpty()
        beat.beat()

        assertThat(halts.single()).contains("failed 3 consecutive times: bad snapshot")
    }

    @Test
    fun `a failed book sample does not escape onto the feed thread`() {
        var sampled = 0L

        sampleBook(1L) { throw EngineSnapshotTimeoutException() }
        sampleBook(2L) { sampled = it }

        assertThat(sampled).isEqualTo(2L)
    }
}
