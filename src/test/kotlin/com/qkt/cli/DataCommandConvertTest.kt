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
            listOf(
                Tick(
                    "XAUUSD",
                    com.qkt.common.Money
                        .of("1711.7"),
                    1_712_016_000_000L,
                ),
            ),
        )

        val rc =
            DataCommand(
                Args(arrayOf("data", "convert", "XAUUSD", "--prune", "--data-root", root.toString())),
            ).run()

        assertEquals(ExitCodes.USER_ERROR, rc)
        assertTrue(Files.exists(symDir.resolve("2024-04-02.csv.gz")))
    }

    @Test
    fun `convert needs a symbol and a symbol folder that exists`(
        @TempDir root: Path,
    ) {
        val noSymbol = captureCliRun { DataCommand(Args(arrayOf("data", "convert"))).run() }
        assertEquals(ExitCodes.ARG_ERROR, noSymbol.code)
        assertEquals(
            "qkt: missing symbol. usage: qkt data convert <symbol> [--from] [--to] [--prune]\n",
            noSymbol.err,
        )

        val unknown =
            captureCliRun {
                DataCommand(
                    Args(arrayOf("data", "convert", "EURUSD", "--data-root", root.toString())),
                ).run()
            }
        assertEquals(ExitCodes.USER_ERROR, unknown.code)
        assertEquals(
            "qkt: no cached tick data for 'EURUSD' at ${root.resolve("symbols").resolve("EURUSD")}\n",
            unknown.err,
        )
    }

    @Test
    fun `from is inclusive and to is exclusive when bounding the days converted`(
        @TempDir root: Path,
    ) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        for (day in listOf("2024-04-01", "2024-04-02", "2024-04-03", "2024-04-04")) {
            writeCsvGz(symDir.resolve("$day.csv.gz"), listOf("1712016000000,XAUUSD,,,1711.5,1712.0,,"))
        }
        Files.writeString(symDir.resolve("notes.txt"), "not a day file")

        val run =
            captureCliRun {
                DataCommand(
                    Args(
                        arrayOf("data", "convert", "XAUUSD", "--from", "2024-04-02", "--to", "2024-04-04") +
                            arrayOf("--data-root", root.toString()),
                    ),
                ).run()
            }

        assertEquals(ExitCodes.SUCCESS, run.code)
        assertEquals("qkt data convert: XAUUSD — converted=2 skipped=0 prune=false\n", run.out)
        assertEquals(
            listOf("2024-04-02.bin", "2024-04-03.bin"),
            Files.list(symDir).use { files ->
                files
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(".bin") }
                    .sorted()
                    .toList()
            },
        )
    }
}
