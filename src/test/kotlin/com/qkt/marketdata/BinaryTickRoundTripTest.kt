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
