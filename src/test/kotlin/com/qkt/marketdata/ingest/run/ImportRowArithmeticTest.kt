package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportRowArithmeticTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun file(text: String): Path = dir.resolve("in.csv").also { Files.writeString(it, text) }

    private val bars =
        ImportDescriptor(
            kind = IngestKind.BARS,
            time = listOf("$0"),
            basis = PriceBasis.MID,
            digits = 5,
            timeframe = "1m",
            fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4", "spread" to "$5"),
        )

    @Test
    fun `a number too large to hold is a row error, not a crash`() {
        val huge = "2024-01-02T10:00:00Z,1e2147483647,1.1,1.1,1.1,0\n"

        assertThatThrownBy { IngestFixtures.import(root, listOf(file(huge)), "DATA:EURUSD", bars, dryRun = true) }
            .isInstanceOf(ImportRefusal::class.java)
    }

    @Test
    fun `a series whose rows are all filtered out has no values to import`() {
        val series =
            ImportDescriptor(
                kind = IngestKind.SERIES,
                time = listOf("date"),
                availableLag = "1d",
                fields = mapOf("value" to "v"),
                where = mapOf("kind" to "keep"),
            )
        val rows = file("date,v,kind\n2024-01-02,5,drop\n2024-01-09,6,drop\n")

        assertThatThrownBy { IngestFixtures.import(root, listOf(rows), "CFTC:X", series, dryRun = true) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("no values to import")
    }
}
