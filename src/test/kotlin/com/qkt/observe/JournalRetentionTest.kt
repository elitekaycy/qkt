package com.qkt.observe

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JournalRetentionTest {
    // 2023-11-14T22:13:20Z
    private val now = 1_700_000_000_000L

    private fun touch(
        root: Path,
        owner: String,
        name: String,
    ): Path {
        val dir = root.resolve(owner)
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve(name), "{}\n")
    }

    @Test
    fun `removes day-files older than the retention window and keeps the rest`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("audit-journal")
        val transport = tmp.resolve("mt5-transport-journal")
        val old = touch(audit, "alpha", "audit-2023-10-30.jsonl")
        val oldMarker = touch(audit, "alpha", "audit-2023-10-30.dropped")
        val edge = touch(audit, "alpha", "audit-2023-10-31.jsonl")
        val recent = touch(audit, "alpha", "audit-2023-11-13.jsonl")
        val today = touch(audit, "alpha", "audit-2023-11-14.jsonl")
        val oldTransport = touch(transport, "icmarkets", "transport-2023-10-01.jsonl")
        val unrelated = touch(audit, "alpha", "notes.txt")

        val removed = JournalRetention(listOf(audit, transport), retentionDays = 14, clock = FixedClock(now)).sweep()

        assertThat(removed).isEqualTo(3)
        assertThat(old).doesNotExist()
        assertThat(oldMarker).doesNotExist()
        assertThat(oldTransport).doesNotExist()
        assertThat(edge).exists()
        assertThat(recent).exists()
        assertThat(today).exists()
        assertThat(unrelated).exists()
    }

    @Test
    fun `zero retention keeps every file`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("audit-journal")
        val old = touch(audit, "alpha", "audit-2020-01-01.jsonl")

        val removed = JournalRetention(listOf(audit), retentionDays = 0, clock = FixedClock(now)).sweep()

        assertThat(removed).isZero()
        assertThat(old).exists()
    }

    @Test
    fun `missing roots are ignored`(
        @TempDir tmp: Path,
    ) {
        val removed =
            JournalRetention(listOf(tmp.resolve("nope")), retentionDays = 14, clock = FixedClock(now)).sweep()

        assertThat(removed).isZero()
    }
}
