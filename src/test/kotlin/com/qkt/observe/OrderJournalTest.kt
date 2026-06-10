package com.qkt.observe

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderJournalTest {
    @Test
    fun `events append as one JSONL line each, per strategy per day`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(time = 1_700_000_000_000L) // 2023-11-14 UTC
        val journal = OrderJournal(tmp, clock)
        journal.append("alpha", "submit", mapOf("id" to "o-1", "symbol" to "XAUUSD"))
        journal.append("alpha", "filled", mapOf("id" to "o-1", "price" to "2000"))
        journal.append("beta", "submit", mapOf("id" to "o-2"))

        val alpha = Files.readAllLines(tmp.resolve("alpha/journal-2023-11-14.jsonl"))
        assertThat(alpha).hasSize(2)
        assertThat(alpha[0]).contains("\"kind\":\"submit\"").contains("\"id\":\"o-1\"")
        assertThat(alpha[1]).contains("\"kind\":\"filled\"").contains("\"price\":\"2000\"")
        assertThat(Files.readAllLines(tmp.resolve("beta/journal-2023-11-14.jsonl"))).hasSize(1)
    }

    @Test
    fun `the journal rolls to a new file at the UTC day boundary`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(time = 1_700_000_000_000L)
        val journal = OrderJournal(tmp, clock)
        journal.append("alpha", "submit", mapOf("id" to "o-1"))
        clock.time += 86_400_000L
        journal.append("alpha", "submit", mapOf("id" to "o-2"))

        assertThat(Files.exists(tmp.resolve("alpha/journal-2023-11-14.jsonl"))).isTrue()
        assertThat(Files.exists(tmp.resolve("alpha/journal-2023-11-15.jsonl"))).isTrue()
    }
}
