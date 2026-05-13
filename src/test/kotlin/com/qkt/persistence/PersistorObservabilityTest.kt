package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PersistorObservabilityTest {
    private fun makeBook() =
        LegBook("XAUUSDm").apply {
            add(
                PositionLeg(
                    legId = "leg-1",
                    symbol = "XAUUSDm",
                    side = Side.BUY,
                    quantity = BigDecimal("0.1"),
                    entryPrice = BigDecimal("4700"),
                    openedAt = 0L,
                    role = LegRole.PRIMARY,
                ),
            )
        }

    @Test
    fun `FileStatePersistor increments totalWrites and totalBytesWritten per save`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.totalWrites).isZero
        assertThat(persistor.totalBytesWritten).isZero

        persistor.saveLegBook("hedge", "XAUUSDm", makeBook())
        assertThat(persistor.totalWrites).isEqualTo(1L)
        assertThat(persistor.totalBytesWritten).isPositive

        persistor.saveBracketPairs("hedge", listOf(BracketPair("c-1", "c-1-sl", "c-1-tp", null)))
        assertThat(persistor.totalWrites).isEqualTo(2L)
    }

    @Test
    fun `slowWrites stays zero on a normal write`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.saveLegBook("hedge", "XAUUSDm", makeBook())
        // 100ms threshold; a temp-dir write is microseconds. Should never trip.
        assertThat(persistor.slowWrites).isZero
    }

    @Test
    fun `AsyncStatePersistor exposes queueSize and callerRunsTotal`(
        @TempDir tmp: Path,
    ) {
        val file = FileStatePersistor(tmp)
        AsyncStatePersistor(file, queueCapacity = 4).use { async ->
            assertThat(async.queueSize).isZero
            assertThat(async.callerRunsTotal).isZero

            // Enqueue a few writes
            repeat(3) { async.saveLegBook("hedge", "XAUUSDm", makeBook()) }
            // queueSize may be 0 after drain or still > 0 depending on timing; just assert non-negative
            assertThat(async.queueSize).isGreaterThanOrEqualTo(0)
            assertThat(async.awaitDrain()).isTrue
            // After drain the queue is empty; callerRuns should still be 0 because the queue
            // (capacity 4) was never full with only 3 enqueued.
            assertThat(async.callerRunsTotal).isZero
        }
    }
}
