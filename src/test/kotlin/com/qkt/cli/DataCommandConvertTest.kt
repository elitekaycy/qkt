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

    @Test
    fun `--prune removes a csv only after its bin reads back the same, a day converted earlier included`(
        @TempDir root: Path,
    ) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        writeCsvGz(
            symDir.resolve("2024-04-02.csv.gz"),
            listOf("1712016000000,XAUUSD,,,1711.50400000,1712.00200000,0.00012000,0.00018000"),
        )
        val convert = { extra: List<String> ->
            DataCommand(Args(arrayOf("data", "convert", "XAUUSD", "--data-root", root.toString()) + extra)).run()
        }
        assertEquals(ExitCodes.SUCCESS, convert(emptyList()))
        assertTrue(Files.exists(symDir.resolve("2024-04-02.csv.gz")))

        assertEquals(ExitCodes.SUCCESS, convert(listOf("--prune")))
        assertTrue(!Files.exists(symDir.resolve("2024-04-02.csv.gz")))
        assertEquals(1, drain(BinaryTickFeed(symDir.resolve("2024-04-02.bin"))).size)
    }

    @Test
    fun `--prune keeps a csv whose bin holds other ticks`(
        @TempDir root: Path,
    ) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        writeCsvGz(
            symDir.resolve("2024-04-02.csv.gz"),
            listOf(
                "1712016000000,XAUUSD,,,1711.50400000,1712.00200000,,",
                "1712016060000,XAUUSD,,,1711.60400000,1712.10200000,,",
            ),
        )
        com.qkt.marketdata.BinaryTickWriter().write(
            symDir.resolve("2024-04-02.bin"),
            "XAUUSD",
            listOf(Tick("XAUUSD", com.qkt.common.Money.of("1711.7"), 1_712_016_000_000L)),
        )

        val rc = DataCommand(Args(arrayOf("data", "convert", "XAUUSD", "--prune", "--data-root", root.toString()))).run()

        assertEquals(ExitCodes.USER_ERROR, rc)
        assertTrue(Files.exists(symDir.resolve("2024-04-02.csv.gz")))
    }
}
