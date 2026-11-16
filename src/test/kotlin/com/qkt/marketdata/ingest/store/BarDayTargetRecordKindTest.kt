package com.qkt.marketdata.ingest.store

import com.qkt.common.FixedClock
import com.qkt.marketdata.ingest.record.TickRecord
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarDayTargetRecordKindTest {
    @Test
    fun `a bar store refuses a tick record`(
        @TempDir root: Path,
    ) {
        val target = BarDayTarget(root, "DATA", "EURUSD", "1m", 60_000L, FixedClock(0L))
        val tick = TickRecord(1_000L, 0L, BigDecimal.ONE, null, null, null, null, null)

        assertThatThrownBy { target.write(LocalDate.of(2026, 1, 1), listOf(tick)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("a bar store cannot hold")
    }
}
