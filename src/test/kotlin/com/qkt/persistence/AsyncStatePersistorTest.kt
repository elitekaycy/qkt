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

class AsyncStatePersistorTest {
    private fun mkPrimary(qty: String = "0.20") =
        PositionLeg(
            legId = "leg-1",
            symbol = "XAUUSDm",
            side = Side.BUY,
            quantity = BigDecimal(qty),
            entryPrice = BigDecimal("4700"),
            openedAt = 0L,
            role = LegRole.PRIMARY,
        )

    @Test
    fun `saveLegBook is queued and eventually flushed to delegate`(
        @TempDir tmp: Path,
    ) {
        val file = FileStatePersistor(tmp)
        AsyncStatePersistor(file).use { async ->
            val book = LegBook("XAUUSDm").apply { add(mkPrimary()) }
            async.saveLegBook("hedge", "XAUUSDm", book)
            // Drain the queue
            assertThat(async.awaitDrain()).isTrue
        }
        // After close, the file is on disk
        val loaded = FileStatePersistor(tmp).loadLegBook("hedge", "XAUUSDm")
        assertThat(loaded!!.legs).hasSize(1)
    }

    @Test
    fun `snapshot freezes the LegBook against post-call mutation`(
        @TempDir tmp: Path,
    ) {
        val delegate = NoopStatePersistor()
        AsyncStatePersistor(delegate).use { async ->
            val book = LegBook("XAUUSDm").apply { add(mkPrimary("0.20")) }
            async.saveLegBook("hedge", "XAUUSDm", book)
            // Mutate the live book AFTER calling save. The async snapshot should not see it.
            book.add(
                PositionLeg(
                    legId = "leg-2",
                    parentLegId = "leg-1",
                    symbol = "XAUUSDm",
                    side = Side.BUY,
                    quantity = BigDecimal("0.06"),
                    entryPrice = BigDecimal("4710"),
                    openedAt = 1L,
                    role = LegRole.STACK,
                ),
            )
            assertThat(async.awaitDrain()).isTrue
            val persisted = delegate.loadLegBook("hedge", "XAUUSDm")!!
            // The async snapshot only saw the single primary leg, not the stack added after.
            assertThat(persisted.legs).hasSize(1)
            assertThat(persisted.legs.single().legId).isEqualTo("leg-1")
        }
    }

    @Test
    fun `loads delegate directly without queueing`(
        @TempDir tmp: Path,
    ) {
        val file = FileStatePersistor(tmp)
        val book = LegBook("XAUUSDm").apply { add(mkPrimary()) }
        file.saveLegBook("hedge", "XAUUSDm", book)
        AsyncStatePersistor(file).use { async ->
            // load should be synchronous (the delegate's read), no executor involved
            val loaded = async.loadLegBook("hedge", "XAUUSDm")
            assertThat(loaded!!.legs).hasSize(1)
        }
    }

    @Test
    fun `close drains pending writes before returning`(
        @TempDir tmp: Path,
    ) {
        val file = FileStatePersistor(tmp)
        val async = AsyncStatePersistor(file)
        val book = LegBook("XAUUSDm").apply { add(mkPrimary()) }
        // Enqueue a write
        async.saveLegBook("hedge", "XAUUSDm", book)
        async.close()
        // After close, the write must be on disk.
        val loaded = FileStatePersistor(tmp).loadLegBook("hedge", "XAUUSDm")
        assertThat(loaded).isNotNull
        assertThat(loaded!!.legs).hasSize(1)
    }
}
