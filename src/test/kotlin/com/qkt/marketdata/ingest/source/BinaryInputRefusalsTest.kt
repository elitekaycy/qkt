package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Binary inputs qkt cannot store are refused by name, and inputs that disagree are not merged. */
class BinaryInputRefusalsTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")
    private val t0 = 1_709_683_200_000_000_000L

    private fun dbn(
        name: String,
        rtype: Int,
        bytes: Int = 16,
    ): Path {
        val record = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        record
            .put((bytes / 4).toByte())
            .put(rtype.toByte())
            .putShort(1)
            .putInt(5482)
            .putLong(t0)
        repeat((bytes - 16) / 8) { record.putLong(5_100_000_000_000L) }
        val file = "DBN".toByteArray() + byteArrayOf(3, 0, 0, 0, 0) + record.array()
        return dir.resolve(name).also { Files.write(it, file) }
    }

    private fun hst(
        folder: String,
        digits: Int,
    ): Path {
        val b = ByteBuffer.allocate(148 + 44).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 400)
        b.put(68, "EURUSD".toByteArray())
        b.putInt(80, 60)
        b.putInt(84, digits)
        b.position(148)
        b
            .putInt(1_709_632_800)
            .putDouble(1.08501)
            .putDouble(1.08455)
            .putDouble(1.08563)
            .putDouble(1.08530)
            .putDouble(1824.0)
        val target = dir.resolve(folder).resolve("EURUSD60.hst")
        Files.createDirectories(target.parent)
        return target.also { Files.write(it, b.array()) }
    }

    @Test
    fun `record types without a series are refused by their Databento schema name`() {
        val names =
            mapOf(
                0x13 to "instrument definition",
                0x18 to "statistics",
                0x12 to "status",
                0x14 to "imbalance",
                0x42 to "type 0x42",
            )

        for ((rtype, name) in names) {
            val file = dbn("t$rtype.dbn", rtype)
            assertThatThrownBy { DbnRecords.input(file, "sha") }
                .isInstanceOf(ImportRefusal::class.java)
                .hasMessageContaining("holds $name records, which qkt has no series for")
        }
    }

    @Test
    fun `bar files of different timeframes are not imported as one series`() {
        val minute = dbn("minute.dbn", 33, bytes = 56)
        val hour = dbn("hour.dbn", 34, bytes = 56)

        assertThatThrownBy { IngestFixtures.import(root, listOf(minute, hour), "DATABENTO:ES") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("the inputs hold bars of different timeframes [1m, 1h]: import them separately")
    }

    @Test
    fun `history files stating different price digits are not imported as one series`() {
        val files = listOf(hst("five", 5), hst("three", 3))

        assertThatThrownBy {
            IngestFixtures.import(root, files, "BROKER:ZZZQQQ", ImportDescriptor(clock = "ny-close"))
        }.isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("the inputs state different price digits [3, 5]: import them separately")
    }

    @Test
    fun `a sierra file whose first record time is neither microseconds nor days is refused`() {
        val b = ByteBuffer.allocate(56 + 40).order(ByteOrder.LITTLE_ENDIAN)
        b
            .put("SCID".toByteArray())
            .putInt(56)
            .putInt(40)
            .putShort(1)
        b.position(56)
        b
            .putLong(1L)
            .putFloat(0f)
            .putFloat(5100.5f)
            .putFloat(5100.25f)
            .putFloat(5100.5f)
        val file = dir.resolve("ES.scid").also { Files.write(it, b.array()) }

        assertThatThrownBy { IngestFixtures.import(root, listOf(file), "SIERRA:ES", ImportDescriptor(digits = 2)) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("the first record's time is neither SCDateTime microseconds nor days")
    }
}
