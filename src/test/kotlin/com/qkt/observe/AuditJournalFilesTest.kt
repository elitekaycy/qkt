package com.qkt.observe

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuditJournalFilesTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val tuesday = LocalDate.parse("2026-11-03")

    @Test
    fun `an owner with no journal has no last sequence`(
        @TempDir tmp: Path,
    ) {
        assertThat(AuditJournalFiles(tmp, "alpha").lastSequence()).isNull()
    }

    @Test
    fun `the last sequence is the highest in the newest day file that has one`(
        @TempDir tmp: Path,
    ) {
        val files = AuditJournalFiles(tmp, "")
        files.append(monday, "{\"seq\":9}\n{\"seq\":4}\n".toByteArray())
        files.append(tuesday, "{\"seq\":12}\n{\"v\":1}\n{\"seq\":11}\n".toByteArray())
        files.append(tuesday, "{\"seq\":3}\n".toByteArray())
        files.close()
        files.close()

        assertThat(files.lastSequence()).isEqualTo(12L)
        assertThat(tmp.resolve("_session/audit-2026-11-02.jsonl")).exists()
    }

    @Test
    fun `a newest day file without sequences falls back to the day before`(
        @TempDir tmp: Path,
    ) {
        val files = AuditJournalFiles(tmp, "a/b")
        files.append(monday, "{\"seq\":7}\n".toByteArray())
        files.append(tuesday, "{\"v\":1}\n".toByteArray())
        files.writeDropMarker(tuesday, 3L)
        Files.writeString(tmp.resolve("a_b/notes.txt"), "{\"seq\":99}")

        assertThat(files.lastSequence()).isEqualTo(7L)
        assertThat(Files.readString(tmp.resolve("a_b/audit-2026-11-03.dropped"))).isEqualTo("3\n")
    }
}
