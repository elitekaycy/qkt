package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickRoundTripTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

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

    @Test
    fun `writer then feed reproduces ticks exactly`(
        @TempDir dir: Path,
    ) {
        val ticks =
            listOf(
                TickAssembler.assemble(
                    "XAUUSD",
                    1_712_000_000_000L,
                    null,
                    null,
                    bd("1711.50400000"),
                    bd("1712.00200000"),
                    bd("0.00012000"),
                    bd("0.00018000"),
                    { "t:1" },
                ),
                TickAssembler.assemble(
                    "XAUUSD",
                    1_712_000_000_050L,
                    null,
                    null,
                    bd("1711.53400000"),
                    bd("1712.00600000"),
                    bd("0.00018000"),
                    bd("0.00012000"),
                    { "t:2" },
                ),
            )
        val file = dir.resolve("2024-01-04.bin")
        BinaryTickWriter().write(file, "XAUUSD", ticks)
        assertEquals(ticks, drain(BinaryTickFeed(file)))
    }

    @Test
    fun `all columns and nulls round-trip exactly across many ticks`(
        @TempDir dir: Path,
    ) {
        fun digits8(n: Int) = "1." + (10_000_000 + n % 80_000_000).toString()
        val ticks =
            (0 until 5000).map { i ->
                TickAssembler.assemble(
                    "EURUSD",
                    1_712_000_000_000L + i,
                    if (i % 7 == 0) null else bd(digits8(i)),
                    if (i % 5 == 0) null else bd("${i % 50}.00000000"),
                    bd(digits8(i + 1)),
                    bd(digits8(i + 2)),
                    if (i % 3 == 0) null else bd("0.000${10_000 + i % 80_000}"),
                    if (i % 4 == 0) null else bd("0.000${20_000 + i % 70_000}"),
                    { "t:$i" },
                )
            }
        val file = dir.resolve("2024-02-01.bin")
        BinaryTickWriter().write(file, "EURUSD", ticks)
        assertEquals(ticks, drain(BinaryTickFeed(file)))
    }

    @Test
    fun `empty tick list writes a readable empty file`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("2024-01-06.bin")
        BinaryTickWriter().write(file, "XAUUSD", emptyList())
        assertEquals(emptyList<Tick>(), drain(BinaryTickFeed(file)))
        assertTrue(Files.exists(file))
    }

    @Test
    fun `non-monotonic timestamps fail loud`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("2024-01-05.bin")
        val ticks =
            listOf(
                TickAssembler.assemble("X", 100L, bd("1.0"), null, null, null, null, null, { "t:1" }),
                TickAssembler.assemble("X", 50L, bd("1.0"), null, null, null, null, null, { "t:2" }),
            )
        BinaryTickWriter().write(file, "X", ticks)
        val feed = BinaryTickFeed(file)
        assertThrows(IllegalStateException::class.java) { drain(feed) }
    }
}
