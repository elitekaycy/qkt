package com.qkt.observe

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderJournalDegradedTest {
    private val clock = FixedClock(time = 1_700_000_000_000L) // 2023-11-14 UTC

    @Test
    fun `appends after close still land durably in the day file`(
        @TempDir tmp: Path,
    ) {
        val journal = OrderJournal(tmp, clock)
        journal.append("alpha", "submit", mapOf("id" to "o-1"))
        journal.close()
        journal.close()
        journal.append("alpha", "notify_failed", mapOf("id" to "o-1"))

        val lines = Files.readAllLines(tmp.resolve("alpha/journal-2023-11-14.jsonl"))
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).contains("\"kind\":\"notify_failed\"")
    }

    @Test
    fun `an unattributed event skips null fields and swaps double quotes`(
        @TempDir tmp: Path,
    ) {
        OrderJournal(tmp, clock).use {
            it.append(" ", "reject", mapOf("reason" to "say \"no\"", "ticket" to null))
        }

        val line = Files.readAllLines(tmp.resolve("_unattributed/journal-2023-11-14.jsonl")).single()
        assertThat(line).isEqualTo("{\"ts\":1700000000000,\"kind\":\"reject\",\"reason\":\"say 'no'\"}")
    }

    @Test
    fun `an unwritable root swallows the failure for open and closed journals`(
        @TempDir tmp: Path,
    ) {
        val root = Files.writeString(tmp.resolve("file"), "x")
        val journal = OrderJournal(root, clock)
        journal.append("alpha", "submit", mapOf("id" to "o-1"))
        journal.append("alpha", "submit", mapOf("id" to "o-2"))
        journal.close()
        journal.append("alpha", "submit", mapOf("id" to "o-3"))

        assertThat(Files.readString(root)).isEqualTo("x")
    }
}
