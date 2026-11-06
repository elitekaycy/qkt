package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DatasetSnapshotsPolicyTest {
    @TempDir
    lateinit var root: Path

    private val monday = LocalDate.of(2026, 1, 5)
    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private fun writeDay(vararg rows: String) {
        val dir = TickStoreKeys.dir(root, "X:SYM")
        Files.createDirectories(dir)
        Files.write(dir.resolve("$monday.csv"), listOf(header) + rows)
    }

    private fun at(time: String) = Instant.parse("${monday}T$time").toEpochMilli()

    @Test
    fun `a snapshot taken with defaults records an unknown version and the default policy`() {
        writeDay("${at("10:00:00Z")},X:SYM,100,1,99,101,1,1")

        val snapshot = DatasetSnapshots.create(root, "X:SYM", monday, monday.plusDays(1), "test")

        assertThat(snapshot.qktVersion).isEqualTo("unknown")
        assertThat(snapshot.gitSha).isEqualTo("unknown")
        assertThat(snapshot.qualityPolicy).isEqualTo(DataQualityPolicy())
        assertThat(DatasetSnapshots.verify(snapshot).ok).isTrue()
    }

    @Test
    fun `days without bid ask or volume fail a policy that requires them`() {
        writeDay("${at("10:00:00Z")},X:SYM,100,,,,,", "${at("10:01:00Z")},X:SYM,100,2,99,101,1,1")
        val policy = DataQualityPolicy(requireBidAsk = true, requireVolume = true)

        val snapshot =
            DatasetSnapshots.create(
                root,
                "X:SYM",
                monday,
                monday.plusDays(1),
                "test",
                qualityPolicy = policy,
            )
        val verification = DatasetSnapshots.verify(snapshot)

        assertThat(verification.ok).isFalse()
        assertThat(verification.failures).containsExactly(
            "$monday: bid/ask required but present on 1/2 ticks",
            "$monday: volume required but present on 1/2 ticks",
        )
    }
}
