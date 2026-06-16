package com.qkt.marketdata

import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickParityTest {
    private fun drain(feed: TickFeed): List<Tick> {
        val out = mutableListOf<Tick>()
        feed.use {
            while (true) {
                val t = it.next() ?: break
                out.add(t)
            }
        }
        return out
    }

    /** Writes a gz CSV day file in the exact schema the dukascopy converter produces. */
    private fun writeCsvGz(
        path: Path,
        rows: List<String>,
    ) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(Files.newOutputStream(path)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                w.write(CsvTickFeed.EXPECTED_HEADER + "\n")
                rows.forEach { w.write(it + "\n") }
            }
        }
    }

    @Test
    fun `binary feed is bit-identical to csv feed`(
        @TempDir dir: Path,
    ) {
        val csv = dir.resolve("2024-01-04.csv.gz")
        // ms-epoch, blank price/volume, bid/ask/vols at 8dp — the real cache schema.
        writeCsvGz(
            csv,
            listOf(
                "1712000000000,XAUUSD,,,1711.50400000,1712.00200000,0.00012000,0.00018000",
                "1712000000050,XAUUSD,,,1711.53400000,1712.00600000,0.00018000,0.00012000",
                "1712000000090,XAUUSD,,,1711.40000000,1711.90000000,0.00010000,0.00010000",
            ),
        )
        val viaCsv = drain(CsvTickFeed(csv))

        val bin = dir.resolve("2024-01-04.bin")
        BinaryTickWriter().write(bin, "XAUUSD", viaCsv)
        val viaBin = drain(BinaryTickFeed(bin))

        assertEquals(viaCsv, viaBin)
    }
}
