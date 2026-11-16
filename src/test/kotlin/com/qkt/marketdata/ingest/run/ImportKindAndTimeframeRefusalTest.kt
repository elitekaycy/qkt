package com.qkt.marketdata.ingest.run

import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.ImportTarget
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.MappingCompiler
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** An import with no kind, or bars whose spacing is no timeframe, refuses before writing. */
class ImportKindAndTimeframeRefusalTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a mapping without a kind refuses and lists the kinds`() {
        val target = ImportTarget.of("DATA:EURUSD", StandardInstrumentRegistry, null)

        assertThatThrownBy { MappingCompiler(ImportDescriptor(), target) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessage("no preset fits and no --kind was given (ticks, bars, funding, open-interest, series)")
    }

    @Test
    fun `bars a second and a half apart are not a whole number of seconds`() {
        val rows =
            (0 until 5).joinToString("") { i ->
                "${1_704_189_600_000L + i * 1_500L},1.1,1.2,1.0,1.1\n"
            }
        val file = dir.resolve("bars.csv").also { Files.writeString(it, rows) }
        val bars =
            ImportDescriptor(
                kind = IngestKind.BARS,
                time = listOf("$0"),
                fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4"),
            )

        assertThatThrownBy {
            IngestFixtures.import(dir.resolve("root"), listOf(file), "DATA:EURUSD", bars, dryRun = true)
        }.isInstanceOf(ImportRefusal::class.java)
            .hasMessage("bars are mostly 1500ms apart, which is not a whole number of seconds; pass --tf")
    }
}
