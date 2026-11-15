package com.qkt.marketdata.hub

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The journal cursor counts lines it cannot parse instead of failing the tail. */
class HubJournalCursorSkipTest {
    private val record =
        """{"v":1,"dataset":"cal.high_impact","scope":"USD","key":"k7","revision":1,""" +
            """"known_at":1789043405000,"effective_at":1789043400000,"availability":"observed","source":"t",""" +
            """"seq":7,"fields":{"actual":"0.7"}}"""

    @Test
    fun `garbage, non-object and incomplete lines are skipped and counted while good records pass`(
        @TempDir root: Path,
    ) {
        val dir = root.resolve("journal").resolve("cal.high_impact").also { it.createDirectories() }
        Files.writeString(
            dir.resolve("2026-09-10.ndjson"),
            "not json\n[1,2]\n{\"dataset\":\"cal.high_impact\"}\n$record\n",
        )

        val cursor = HubJournalCursor(root, "cal.high_impact")

        assertThat(cursor.poll().map { it.seq to it.fields["actual"] })
            .containsExactly(7L to java.math.BigDecimal("0.7"))
        assertThat(cursor.skipped).isEqualTo(3L)
    }
}
