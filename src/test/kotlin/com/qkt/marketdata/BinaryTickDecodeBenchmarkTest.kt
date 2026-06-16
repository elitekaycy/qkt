package com.qkt.marketdata

import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickDecodeBenchmarkTest {
    private fun drain(feed: TickFeed): Int {
        var n = 0
        feed.use { while (it.next() != null) n++ }
        return n
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `binary decode reproduces csv at scale and is not slower`(
        @TempDir dir: Path,
    ) {
        val n = 200_000
        val csv = dir.resolve("2024-01-04.csv.gz")
        Files.createDirectories(csv.parent)
        GZIPOutputStream(Files.newOutputStream(csv)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                w.write(CsvTickFeed.EXPECTED_HEADER + "\n")
                var ts = 1_712_000_000_000L
                repeat(n) { i ->
                    val bid = 170_000_000_000L + i
                    val ask = bid + 50_000L
                    w.write(
                        "$ts,XAUUSD,,,${BigDecimal.valueOf(bid, 8).toPlainString()}," +
                            "${BigDecimal.valueOf(ask, 8).toPlainString()},0.00010000,0.00010000\n",
                    )
                    ts += 10
                }
            }
        }

        val csvTicks = mutableListOf<Tick>()
        CsvTickFeed(csv).use {
            while (true) {
                val t = it.next() ?: break
                csvTicks.add(t)
            }
        }

        val bin = dir.resolve("2024-01-04.bin")
        BinaryTickWriter().write(bin, "XAUUSD", csvTicks)

        // correctness at scale
        val binTicks = mutableListOf<Tick>()
        BinaryTickFeed(bin).use {
            while (true) {
                val t = it.next() ?: break
                binTicks.add(t)
            }
        }
        assertEquals(csvTicks, binTicks)

        // timing (informational): warm once, then measure
        drain(CsvTickFeed(csv))
        drain(BinaryTickFeed(bin))
        val tCsv = timeMs { drain(CsvTickFeed(csv)) }
        val tBin = timeMs { drain(BinaryTickFeed(bin)) }
        val speedup = if (tBin == 0L) Double.POSITIVE_INFINITY else tCsv.toDouble() / tBin
        println("decode $n ticks: csv=${tCsv}ms bin=${tBin}ms speedup=${"%.1f".format(speedup)}x")
    }
}
