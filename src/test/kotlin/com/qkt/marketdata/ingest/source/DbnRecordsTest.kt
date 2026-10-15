package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Databento DBN edge cases, written byte by byte as the DBN encoder writes them: a version 3 prefix with
 * an empty metadata block, then records. The real conformance files are in `NativeSampleCases`.
 */
class DbnRecordsTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")
    private val t0 = 1_709_683_200_000_000_000L
    private val undef = Long.MAX_VALUE

    private fun record(
        rtype: Int,
        instrument: Int,
        tsEvent: Long,
        bytes: Int,
        body: ByteBuffer.() -> Unit,
    ): ByteArray =
        ByteBuffer
            .allocate(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put((bytes / 4).toByte())
                    .put(rtype.toByte())
                    .putShort(1)
                    .putInt(instrument)
                    .putLong(tsEvent)
                body()
            }.array()

    private fun trade(
        instrument: Int = 5482,
        ts: Long = t0,
        price: Long = 5_100_250_000_000L,
    ) = record(0, instrument, ts, 48) {
        putLong(price)
            .putInt(3)
            .put('T'.code.toByte())
            .put('A'.code.toByte())
            .put(0)
            .put(0)
            .putLong(ts + 20_000)
    }

    private fun mbp1(
        action: Char,
        bid: Long,
        ask: Long,
        ts: Long = t0,
    ) = record(1, 5482, ts, 80) {
        putLong(bid)
            .putInt(2)
            .put(action.code.toByte())
            .put('B'.code.toByte())
            .put(0)
            .put(0)
            .putLong(ts + 15_000)
        putInt(0)
            .putInt(0)
            .putLong(bid)
            .putLong(ask)
            .putInt(11)
            .putInt(12)
            .putInt(1)
            .putInt(1)
    }

    private fun heartbeat() = record(0x17, 0, t0, 320) {}

    private fun dbn(
        name: String,
        vararg records: ByteArray,
        version: Int = 3,
    ): Path {
        val out = ByteArrayOutputStream()
        out.write("DBN".toByteArray())
        out.write(version)
        out.write(byteArrayOf(0, 0, 0, 0))
        records.forEach { out.write(it) }
        return dir.resolve(name).also { Files.write(it, out.toByteArray()) }
    }

    @Test
    fun `trades read at their receive time and heartbeats of a live capture are skipped`() {
        val file = dbn("live.dbn", heartbeat(), trade(), heartbeat(), trade(ts = t0 + 500_000))

        val report = IngestFixtures.import(root, listOf(file), "DATABENTO:ES")

        assertThat(report.preset).isEqualTo("databento-trades")
        val rows = IngestFixtures.dataFiles(root).flatMap { IngestFixtures.lines(it).drop(1) }
        assertThat(rows).containsExactly(
            "1709683200000,ES,5100.25000000,3.00000000,,,,",
            "1709683200000,ES,5100.25000000,3.00000000,,,,",
        )
    }

    @Test
    fun `a one-sided book skips its updates, keeps its trades and says so`() {
        val file =
            dbn(
                "book.dbn",
                mbp1('A', 5_100_000_000_000L, 5_100_250_000_000L),
                mbp1('A', 5_100_000_000_000L, undef, ts = t0 + 1_000_000),
                mbp1('T', 5_100_000_000_000L, undef, ts = t0 + 2_000_000),
            )

        val report = IngestFixtures.import(root, listOf(file), "DATABENTO:ES")

        val rows = IngestFixtures.dataFiles(root).flatMap { IngestFixtures.lines(it).drop(1) }
        assertThat(rows).containsExactly(
            "1709683200000,ES,,,5100.00000000,5100.25000000,11.00000000,12.00000000",
            "1709683200002,ES,5100.00000000,2.00000000,,,,",
        )
        assertThat(report.warnings).anyMatch { it.contains("1 book update(s) with an empty side skipped") }
    }

    @Test
    fun `two instruments or two record types in one file are refused`() {
        val two = dbn("two.dbn", trade(), trade(instrument = 5483))
        assertThatThrownBy { IngestFixtures.import(root, listOf(two), "DATABENTO:ES") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("holds instruments 5482 and 5483")
        val mixed = dbn("mixed.dbn", trade(), mbp1('A', 1, 2))
        assertThatThrownBy { IngestFixtures.import(root, listOf(mixed), "DATABENTO:ES") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("mixes record types trades and mbp-1/tbbo")
    }

    @Test
    fun `a truncated record, an unknown version, order-book records and zstd are refused`() {
        val cut = dbn("cut.dbn", trade().copyOf(40))
        assertThatThrownBy { IngestFixtures.import(root, listOf(cut), "DATABENTO:ES") }
            .hasMessageContaining("record at byte 8 is truncated")
        val v9 = dbn("v9.dbn", trade(), version = 9)
        assertThatThrownBy { IngestFixtures.import(root, listOf(v9), "DATABENTO:ES") }
            .hasMessageContaining("DBN version 9")
        val mbo = IngestFixtures.sample("test_data.mbo.v3.dbn", dir.resolve("in"))
        assertThatThrownBy { IngestFixtures.import(root, listOf(mbo), "DATABENTO:ES") }
            .hasMessageContaining("holds mbo records")
        val zst =
            dir
                .resolve(
                    "x.dbn.zst",
                ).also { Files.write(it, byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())) }
        assertThatThrownBy { IngestFixtures.import(root, listOf(zst), "DATABENTO:ES") }
            .hasMessageContaining("zstd -d x.dbn.zst")
    }
}
