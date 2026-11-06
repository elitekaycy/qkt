package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportPointOverlapTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private val funding =
        ImportDescriptor(
            kind = IngestKind.FUNDING,
            time = listOf("$0"),
            fields = mapOf("rate" to "$1", "price" to "$2"),
        )

    private fun file(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    @Test
    fun `funding stored with other values refuses a re-import told to refuse overlaps`() {
        val first = file("a.csv", "2024-01-02T00:00:00Z,0.0001,42000\n2024-01-02T08:00:00Z,0.0002,42100\n")
        val changed = file("b.csv", "2024-01-02T00:00:00Z,0.0001,42000\n2024-01-02T08:00:00Z,0.0003,42100\n")
        IngestFixtures.import(root, listOf(first), "DATA:BTCUSDT", funding)

        val refusal =
            try {
                IngestFixtures.import(root, listOf(changed), "DATA:BTCUSDT", funding, onOverlap = OverlapPolicy.REFUSE)
                throw AssertionError("expected a refusal")
            } catch (e: ImportRefusal) {
                e
            }

        assertThat(refusal.message).startsWith("1 time(s) are already stored for DATA:BTCUSDT with different values")
        assertThat(refusal.lines).containsExactly("2024-01-02T08:00:00Z")
    }
}
