package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryBarRoundTripTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    private fun v(x: Int) = bd("1850." + "%08d".format(x % 90_000_000))

    @Test
    fun `writer then feed reproduces bars exactly`(
        @TempDir dir: Path,
    ) {
        val tf = 900_000L // 15m
        val bars =
            (0 until 5000).map { i ->
                val base = 1_712_000_000_000L + i * tf
                Candle(
                    "XAUUSD",
                    v(i),
                    v(i + 11_111),
                    v(i + 22_222),
                    v(i + 33_333),
                    bd("${i % 1000}.00000000"),
                    base,
                    base + tf,
                )
            }
        val file = dir.resolve("2024-01-04.bin")
        BinaryBarWriter().write(file, "XAUUSD", tf, bars)
        assertEquals(bars, BinaryBarFeed(file).candles())
    }

    @Test
    fun `empty bar list round-trips`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("empty.bin")
        BinaryBarWriter().write(file, "XAUUSD", 900_000L, emptyList())
        assertEquals(emptyList<Candle>(), BinaryBarFeed(file).candles())
    }
}
