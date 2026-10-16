package com.qkt.marketdata.ingest.compare

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SeriesCompareTest {
    private val point = BigDecimal("0.001")
    private val walk: List<BigDecimal> =
        Random(7).let { r ->
            generateSequence(
                BigDecimal("2000"),
            ) { it.add(BigDecimal(r.nextInt(-50, 51)).movePointLeft(3)) }.take(600).toList()
        }

    private fun series(
        shiftMs: Long = 0,
        bump: String = "0",
    ): List<Candle> =
        (0 until 600).map { i ->
            val c = walk[i].add(BigDecimal(bump))
            val t = i * 60_000L + shiftMs
            Candle("S:X", c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, BigDecimal.TEN, t, t + 60_000)
        }

    @Test
    fun `the same bars are identical`() {
        assertThat(SeriesCompare.compare(series(), series(), 60_000, point).verdict).isEqualTo("identical")
    }

    @Test
    fun `a series read on a clock two hours off shows the offset instead of noise`() {
        val result = SeriesCompare.compare(series(), series(shiftMs = 7_200_000), 60_000, point)

        assertThat(result.offsetMs).isEqualTo(7_200_000)
        assertThat(result.verdict).isEqualTo("differs")
    }

    @Test
    fun `a constant one-point gap on the same clock matches but is not identical`() {
        val result = SeriesCompare.compare(series(), series(bump = "0.001"), 60_000, point)

        assertThat(result.offsetMs).isZero()
        assertThat(result.maxClose).isEqualByComparingTo("1")
        assertThat(result.verdict).isEqualTo("matches")
    }

    @Test
    fun `a different volume alone stops identical`() {
        val other = series().map { it.copy(volume = BigDecimal("11")) }

        assertThat(SeriesCompare.compare(series(), other, 60_000, point).otherFieldDiffs).isEqualTo(600)
    }
}
