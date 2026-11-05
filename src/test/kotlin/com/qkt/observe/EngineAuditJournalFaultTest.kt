package com.qkt.observe

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * #1645: an OutOfMemoryError while writing killed the journal's worker thread; every later fill (a timed close among
 * them) was queued and never written, with nothing counted as lost. The worker now records the failed batch as lost,
 * marks the day's gap on disk, and keeps writing.
 */
class EngineAuditJournalFaultTest {
    private val clock = FixedClock(time = 1_700_000_000_000L)

    private fun fill(id: String) =
        BrokerEvent.OrderFilled(
            clientOrderId = id,
            brokerOrderId = "b-$id",
            symbol = "EXNESS_O12X:XAUUSD",
            side = Side.SELL,
            price = Money.of("3925.918"),
            quantity = Money.of("0.01"),
            strategyId = "soak_o12_xau",
            timestamp = clock.now(),
        )

    @Test
    fun `an out-of-memory error in one write loses that batch, records the gap and later fills are still written`(
        @TempDir tmp: Path,
    ) {
        val failOnce = AtomicBoolean(true)
        val journal =
            EngineAuditJournal(tmp, "soak_o12_xau", clock, 50_000) { event ->
                if (failOnce.getAndSet(false)) throw OutOfMemoryError("GC overhead limit exceeded")
                encodeAuditLine(event)
            }
        journal.append(fill("entry"))
        assertThat(journal.awaitDrainedForTesting(5_000L)).isTrue()

        journal.append(fill("exit-close"))
        journal.close()

        val lines = Files.readAllLines(tmp.resolve("soak_o12_xau/audit-2023-11-14.jsonl"))
        assertThat(lines).singleElement().asString().contains("exit-close")
        assertThat(journal.health().lostDecisionEvents).isEqualTo(1L)
        assertThat(Files.readString(tmp.resolve("soak_o12_xau/audit-2023-11-14.dropped")).trim()).isEqualTo("1")
    }
}
