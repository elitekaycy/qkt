package com.qkt.cli

import com.qkt.marketdata.BinaryTickFeed
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataCommandConvertTest {
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

    private fun drain(feed: BinaryTickFeed): List<Tick> {
        val out = mutableListOf<Tick>()
        feed.use {
            while (true) {
                val t = it.next() ?: break
                out.add(t)
            }
        }
        return out
    }

    @Test
    fun `convert writes a bin file per csv day and is idempotent`(
        @TempDir root: Path,
    ) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        writeCsvGz(
            symDir.resolve("2024-04-02.csv.gz"),
            listOf("1712016000000,XAUUSD,,,1711.50400000,1712.00200000,0.00012000,0.00018000"),
        )

        val rc = DataCommand(Args(arrayOf("data", "convert", "XAUUSD", "--data-root", root.toString()))).run()
        assertEquals(ExitCodes.SUCCESS, rc)
        assertTrue(Files.exists(symDir.resolve("2024-04-02.bin")))
        assertEquals(1, drain(BinaryTickFeed(symDir.resolve("2024-04-02.bin"))).size)

        // second run is a no-op (already converted)
        val rc2 = DataCommand(Args(arrayOf("data", "convert", "XAUUSD", "--data-root", root.toString()))).run()
        assertEquals(ExitCodes.SUCCESS, rc2)
    }
}
