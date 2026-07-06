package com.qkt.observe.insights

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InsightsJournalTest {
    @Test
    fun `persists rows and returns only unacked entries`(
        @TempDir tmp: Path,
    ) {
        val journal = InsightsJournal(tmp, "qkt-prod")
        val entries = journal.append(listOf("""{"id":"a"}""", """{"id":"b"}"""))

        assertThat(entries.map { it.journalSeq }).containsExactly(1L, 2L)
        assertThat(journal.pending(10).map { it.eventJson }).containsExactly("""{"id":"a"}""", """{"id":"b"}""")

        journal.ack(1L)

        val reopened = InsightsJournal(tmp, "qkt-prod")
        assertThat(reopened.pending(10).map { it.eventJson }).containsExactly("""{"id":"b"}""")
        assertThat(Files.readString(tmp.resolve("qkt-prod.cursor")).trim()).isEqualTo("1")

        reopened.ack(2L)
        assertThat(Files.size(tmp.resolve("qkt-prod.jsonl"))).isEqualTo(0L)
        val afterCompaction = InsightsJournal(tmp, "qkt-prod")
        assertThat(afterCompaction.append(listOf("""{"id":"c"}""")).single().journalSeq).isEqualTo(3L)
    }

    @Test
    fun `sanitizes instance id in filenames`(
        @TempDir tmp: Path,
    ) {
        val journal = InsightsJournal(tmp, "prod/eu west")
        journal.append(listOf("""{"id":"a"}"""))

        assertThat(tmp.resolve("prod_eu_west.jsonl")).exists()
    }
}
