package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DayFileIntegrityTest {
    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private fun write(
        dir: Path,
        name: String,
        lines: List<String>,
    ): Path {
        val p = dir.resolve(name)
        Files.write(p, lines)
        return p
    }

    @Test
    fun `inspect counts ticks and the largest inter-tick gap`(
        @TempDir dir: Path,
    ) {
        val p =
            write(
                dir,
                "d.csv",
                listOf(
                    header,
                    "0,X,100,,,,,",
                    "60000,X,101,,,,,",
                    "180000,X,102,,,,,", // 120s gap from the previous — the largest
                ),
            )
        val q = DayFileIntegrity.inspect(p)
        assertThat(q.readable).isTrue
        assertThat(q.tickCount).isEqualTo(3)
        assertThat(q.maxGapMs).isEqualTo(120_000L)
        assertThat(q.isEmpty).isFalse
    }

    @Test
    fun `inspect reports a header-only file as readable with zero ticks`(
        @TempDir dir: Path,
    ) {
        val q = DayFileIntegrity.inspect(write(dir, "d.csv", listOf(header)))
        assertThat(q.readable).isTrue
        assertThat(q.tickCount).isEqualTo(0)
        assertThat(q.isEmpty).isTrue
    }

    @Test
    fun `inspect reports a corrupt file as unreadable instead of throwing`(
        @TempDir dir: Path,
    ) {
        // Wrong header → CsvTickFeed throws on open; inspect must swallow and report unreadable.
        val q = DayFileIntegrity.inspect(write(dir, "d.csv", listOf("garbage,not,a,header", "0,X,100,,,,,")))
        assertThat(q.readable).isFalse
    }
}
