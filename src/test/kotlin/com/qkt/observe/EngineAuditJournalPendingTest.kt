package com.qkt.observe

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EngineAuditJournalPendingTest {
    @Test
    fun `a drained journal has nothing pending`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(1_700_000_000_000L)
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        journal.append(TickEvent(Tick("XAUUSD", Money.of("2000"), clock.now()), timestamp = clock.now()))

        assertThat(journal.awaitDrainedForTesting(5_000L)).isTrue
        assertThat(journal.pendingForTesting()).isZero
        journal.close()
    }
}
