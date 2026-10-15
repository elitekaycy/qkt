package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * MetaTrader 4 `.hst` files, both record layouts: written here byte by byte as MT4 writes them, and two real
 * files (the first 60 bars of each of one broker's EURUSD H4 histories, version 401 and version 400).
 */
class HstBarsTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private data class Bar(
        val server: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val spread: Int,
    )

    private val bars =
        listOf(
            Bar("2024-03-05T10:00", 1.08501, 1.08563, 1.08455, 1.08530, 1824, 6),
            Bar("2024-03-05T11:00", 1.08530, 1.08547, 1.08481, 1.08499, 1310, 8),
        )

    private fun epoch(server: String): Long = LocalDateTime.parse(server).toEpochSecond(ZoneOffset.UTC)

    private fun header(
        version: Int,
        digits: Int = 5,
    ): ByteBuffer =
        ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, version)
            put(68, "EURUSD".toByteArray())
            putInt(80, 60)
            putInt(84, digits)
        }

    private fun hst(
        version: Int,
        name: String = "EURUSD60.hst",
        cut: Int = 0,
    ): Path {
        val size = if (version == 401) 60 else 44
        val buf = ByteBuffer.allocate(148 + size * bars.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(header(version).array())
        for (b in bars) {
            if (version == 401) {
                buf
                    .putLong(epoch(b.server))
                    .putDouble(b.open)
                    .putDouble(b.high)
                    .putDouble(b.low)
                    .putDouble(b.close)
                buf.putLong(b.volume).putInt(b.spread).putLong(0)
            } else {
                buf
                    .putInt(epoch(b.server).toInt())
                    .putDouble(b.open)
                    .putDouble(b.low)
                    .putDouble(b.high)
                buf.putDouble(b.close).putDouble(b.volume.toDouble())
            }
        }
        return dir.resolve(name).also { Files.write(it, buf.array().copyOf(buf.capacity() - cut)) }
    }

    @Test
    fun `a version 401 file imports on the broker clock with prices shifted to mid by its spread`() {
        val report = IngestFixtures.import(root, listOf(hst(401)), "BROKER:EURUSD")

        assertThat(report.preset).isEqualTo("mt4-hst")
        assertThat(IngestFixtures.firstStoredRow(root))
            .isEqualTo("1709625600000,1.08504000,1.08566000,1.08458000,1.08533000,1824.00000000")
    }

    @Test
    fun `a version 400 file reads its low before its high and stores the same bars without a spread`() {
        IngestFixtures.import(root, listOf(hst(400)), "BROKER:EURUSD")

        assertThat(IngestFixtures.firstStoredRow(root))
            .isEqualTo("1709625600000,1.08501000,1.08563000,1.08455000,1.08530000,1824.00000000")
    }

    @Test
    fun `the header's digits size the spread when the symbol is unknown and --digits wins over them`() {
        IngestFixtures.import(root, listOf(hst(401)), "BROKER:ZZZQQQ")
        assertThat(IngestFixtures.firstStoredRow(root)).startsWith("1709625600000,1.08504000,")

        IngestFixtures.import(dir.resolve("r4"), listOf(hst(401)), "BROKER:ZZZQQQ", ImportDescriptor(digits = 4))
        assertThat(IngestFixtures.firstStoredRow(dir.resolve("r4"))).startsWith("1709625600000,1.08531000,")
    }

    @Test
    fun `a truncated file or an unknown version is refused, not half read`() {
        assertThatThrownBy { IngestFixtures.import(root, listOf(hst(401, cut = 7)), "BROKER:EURUSD") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("53 trailing bytes")
        val odd = dir.resolve("odd.hst").also { Files.write(it, header(509).array()) }
        assertThatThrownBy { IngestFixtures.import(root, listOf(odd), "BROKER:EURUSD") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("unknown .hst version 509")
    }

    @Test
    fun `real 401 and 400 files of one history store identical bars`() {
        val real = listOf("EURUSD240.hst", "EURUSD240_v400.hst").map { IngestFixtures.sample(it, dir.resolve("in")) }
        val utc = ImportDescriptor(clock = "UTC")
        IngestFixtures.import(dir.resolve("a"), listOf(real[0]), "BROKER:EURUSD", utc, trustClock = true)
        IngestFixtures.import(dir.resolve("b"), listOf(real[1]), "BROKER:EURUSD", utc, trustClock = true)

        val a = IngestFixtures.dataFiles(dir.resolve("a")).flatMap { IngestFixtures.lines(it).drop(1) }
        val b = IngestFixtures.dataFiles(dir.resolve("b")).flatMap { IngestFixtures.lines(it).drop(1) }
        assertThat(a).hasSize(60).isEqualTo(b)
        assertThat(a.first()).isEqualTo("1261353600000,1.43110000,1.43470000,1.43110000,1.43420000,5504.00000000")
    }
}
