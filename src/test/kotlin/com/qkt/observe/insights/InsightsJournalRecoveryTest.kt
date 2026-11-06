package com.qkt.observe.insights

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InsightsJournalRecoveryTest {
    @Test
    fun `an empty journal has nothing pending`(
        @TempDir tmp: Path,
    ) {
        val journal = InsightsJournal(tmp, "")

        assertThat(journal.hasPending()).isFalse
        assertThat(journal.pendingCount()).isZero
        assertThat(journal.pending(5)).isEmpty()
        assertThat(journal.append(emptyList())).isEmpty()
    }

    @Test
    fun `a blank instance id journals under qkt`(
        @TempDir tmp: Path,
    ) {
        InsightsJournal(tmp, " ").append(listOf("""{"id":"a"}"""))

        assertThat(tmp.resolve("qkt.jsonl")).exists()
    }

    @Test
    fun `malformed rows are skipped when counting and replaying`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(
            tmp.resolve("p.jsonl"),
            "1\t{\"id\":\"a\"}\nnot-a-row\n\tno-seq\nx\t{\"id\":\"bad\"}\n7\t\n2\t{\"id\":\"b\"}\n",
        )
        val journal = InsightsJournal(tmp, "p")

        assertThat(journal.pendingCount()).isEqualTo(2L)
        assertThat(journal.hasPending()).isTrue
        assertThat(journal.pending(10).map { it.eventJson }).containsExactly("""{"id":"a"}""", """{"id":"b"}""")
        assertThat(journal.append(listOf("{}")).single().journalSeq).isEqualTo(3L)
    }

    @Test
    fun `acknowledged rows no longer count as pending and older acks are ignored`(
        @TempDir tmp: Path,
    ) {
        val journal = InsightsJournal(tmp, "p")
        journal.append(listOf("a", "b", "c"))
        journal.ack(2L)
        journal.ack(1L)

        assertThat(journal.pendingCount()).isEqualTo(1L)
        assertThat(Files.readString(tmp.resolve("p.cursor")).trim()).isEqualTo("2")
    }

    @Test
    fun `an unreadable cursor replays everything and a cursor past the journal sets the next sequence`(
        @TempDir tmp: Path,
    ) {
        InsightsJournal(tmp, "p").append(listOf("a"))
        Files.writeString(tmp.resolve("p.cursor"), "garbage")
        assertThat(InsightsJournal(tmp, "p").pendingCount()).isEqualTo(1L)

        Files.writeString(tmp.resolve("p.cursor"), "40\n")
        val ahead = InsightsJournal(tmp, "p")
        assertThat(ahead.pendingCount()).isZero
        assertThat(ahead.append(listOf("b")).single().journalSeq).isEqualTo(41L)
    }
}
