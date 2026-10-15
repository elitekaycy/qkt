package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Sierra Chart `.scid` edge cases, written byte by byte in Sierra's documented layout. */
class ScidRecordsTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")
    private val scEpochUs = 25_569L * 86_400_000_000L

    /** 2024-03-05T23:00Z in SCDateTime microseconds. */
    private val t0 = 1_709_679_600_000_000L + scEpochUs

    private data class Rec(
        val time: Long,
        val open: Float,
        val high: Float,
        val low: Float,
        val close: Float,
        val volume: Int = 4,
    )

    private fun scid(
        name: String,
        records: List<Rec>,
        recordSize: Int = 40,
    ): Path {
        val b = ByteBuffer.allocate(56 + 40 * records.size).order(ByteOrder.LITTLE_ENDIAN)
        b
            .put("SCID".toByteArray())
            .putInt(56)
            .putInt(recordSize)
            .putShort(1)
        b.position(56)
        for (r in records) {
            b
                .putLong(r.time)
                .putFloat(r.open)
                .putFloat(r.high)
                .putFloat(r.low)
                .putFloat(r.close)
            b
                .putInt(1)
                .putInt(r.volume)
                .putInt(0)
                .putInt(r.volume)
        }
        return dir.resolve(name).also { Files.write(it, b.array()) }
    }

    private fun rows(): List<String> = IngestFixtures.dataFiles(root).flatMap { IngestFixtures.lines(it).drop(1) }

    @Test
    fun `trades and sub-trades store price, bid and ask rounded to the digits`() {
        val sub = -1.99900095e37f
        val file =
            scid(
                "ES.scid",
                listOf(Rec(t0, 0f, 5100.5f, 5100.25f, 5100.5f), Rec(t0 + 1, sub, 5100.5f, 5100.25f, 5100.25f)),
            )

        val report = IngestFixtures.import(root, listOf(file), "SIERRA:ES", ImportDescriptor(digits = 2))

        assertThat(report.preset).isEqualTo("sierra-scid-ticks")
        assertThat(rows()).containsExactly(
            "1709679600000,ES,5100.50000000,4.00000000,5100.25000000,5100.50000000,,",
            "1709679600000,ES,5100.25000000,4.00000000,5100.25000000,5100.50000000,,",
        )
    }

    @Test
    fun `float32 noise is rounded away and a zero bid leaves the trade without a quote`() {
        val file = scid("EU.scid", listOf(Rec(t0, 0f, 1.08524f, 0f, 1.08523f)))

        IngestFixtures.import(root, listOf(file), "SIERRA:EURUSD", ImportDescriptor(digits = 5))

        assertThat(rows()).containsExactly("1709679600000,EURUSD,1.08523000,4.00000000,,,,")
    }

    @Test
    fun `old files time bars by a day count since 1899-12-30`() {
        val days = { minute: Int -> java.lang.Double.doubleToLongBits(45_356.0 + minute / 1440.0) }
        val file =
            scid(
                "EU_M1.scid",
                listOf(
                    Rec(days(1), 1.085f, 1.0852f, 1.0849f, 1.0851f),
                    Rec(days(2), 1.0851f, 1.0853f, 1.085f, 1.0852f),
                ),
            )

        val report = IngestFixtures.import(root, listOf(file), "SIERRA:EURUSD", ImportDescriptor(digits = 5))

        assertThat(report.preset).isEqualTo("sierra-scid-bars")
        assertThat(rows().first()).isEqualTo("1709596860000,1.08500000,1.08520000,1.08490000,1.08510000,4.00000000")
    }

    @Test
    fun `missing digits, mixed records and another record size are refused`() {
        val ticks = scid("a.scid", listOf(Rec(t0, 0f, 2f, 1f, 1f)))
        assertThatThrownBy { IngestFixtures.import(root, listOf(ticks), "SIERRA:ZZZ") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("pass --digits")
        val mixed = scid("b.scid", listOf(Rec(t0, 0f, 2f, 1f, 1f), Rec(t0 + 60_000_000, 1f, 2f, 1f, 1f)))
        assertThatThrownBy { IngestFixtures.import(root, listOf(mixed), "SIERRA:ES", ImportDescriptor(digits = 2)) }
            .hasMessageContaining("mixes trade records and bar records (record 2)")
        val wide = scid("c.scid", listOf(Rec(t0, 0f, 2f, 1f, 1f)), recordSize = 44)
        assertThatThrownBy { IngestFixtures.import(root, listOf(wide), "SIERRA:ES", ImportDescriptor(digits = 2)) }
            .hasMessageContaining("record size 44")
    }
}
