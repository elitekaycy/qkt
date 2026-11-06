package com.qkt.observe

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JournalRetentionCompressFailureTest {
    // 2023-11-14T22:13:20Z
    private val clock = FixedClock(1_700_000_000_000L)

    private fun retention(root: Path) = JournalRetention(listOf(root), retentionDays = 0, clock = clock, compressAfterDays = 7)

    private fun dayFile(root: Path): Path {
        val dir = Files.createDirectories(root.resolve("alpha"))
        return Files.writeString(dir.resolve("audit-2023-11-01.jsonl"), "{}\n")
    }

    @Test
    fun `a day file whose archive already exists is left alone`(
        @TempDir tmp: Path,
    ) {
        val file = dayFile(tmp)
        val archive = Files.writeString(file.resolveSibling("audit-2023-11-01.jsonl.gz"), "older archive")

        assertThat(retention(tmp).sweep()).isEqualTo(JournalRetention.Result(removed = 0, compressed = 0))
        assertThat(file).exists()
        assertThat(Files.readString(archive)).isEqualTo("older archive")
    }

    @Test
    fun `a day file that cannot be compressed is kept uncompressed`(
        @TempDir tmp: Path,
    ) {
        val file = dayFile(tmp)
        val blockedTemp = Files.createDirectories(file.resolveSibling(".audit-2023-11-01.jsonl.gz.tmp"))
        Files.writeString(blockedTemp.resolve("keep"), "x")

        assertThat(retention(tmp).sweep()).isEqualTo(JournalRetention.Result(removed = 0, compressed = 0))
        assertThat(Files.readString(file)).isEqualTo("{}\n")
        assertThat(file.resolveSibling("audit-2023-11-01.jsonl.gz")).doesNotExist()
    }
}
