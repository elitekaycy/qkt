package com.qkt.marketdata.hub

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The live tail's journal cursor: only appended bytes, complete lines only, and safe across a repair. */
class HubJournalCursorTest {
    private val effectiveAt = 1_789_043_400_000L

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/cal.high_impact.ndjson")).use { it.readAllBytes() }

    private fun manifest(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/manifest.json")).use { it.readAllBytes() }

    private fun store(root: Path): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        dir.resolve("2026-09-10.ndjson").writeBytes(fixture())
        root.resolve("manifest.json").writeBytes(manifest())
        Files.writeString(root.resolve("heartbeat"), "1")
        return root
    }

    private fun record(
        seq: Long,
        scope: String,
        knownAt: Long,
        actual: String,
    ): String =
        """{"v":1,"dataset":"cal.high_impact","scope":"$scope","key":"k$seq","revision":1,""" +
            """"known_at":$knownAt,"effective_at":$effectiveAt,"availability":"observed","source":"t",""" +
            """"seq":$seq,"fields":{"impact":2,"forecast":"0.2","actual":"$actual"}}"""

    @Test
    fun `the cursor reads only what was appended, waits for a torn line and survives a repair`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val cursor = HubJournalCursor(root, "cal.high_impact")
        val initial = cursor.poll()
        assertThat(initial).isNotEmpty
        assertThat(cursor.poll()).isEmpty()

        val line = record(99, "USD", effectiveAt + 5_000L, "0.7")
        Files.writeString(file, Files.readString(file) + line.substring(0, 30))
        assertThat(cursor.poll()).isEmpty()
        Files.writeString(file, Files.readString(file) + line.substring(30) + "\n")
        assertThat(cursor.poll().map { it.seq }).containsExactly(99L)
        assertThat(cursor.poll()).isEmpty()

        // A repaired (shorter) file is read again from its start; the caller's sequence guard
        // decides what is new, so nothing is lost and nothing is invented.
        Files.writeString(file, record(1, "USD", effectiveAt, "0.1") + "\n")
        assertThat(cursor.poll().map { it.seq }).containsExactly(1L)
        assertThat(cursor.skipped).isZero()
    }
}
