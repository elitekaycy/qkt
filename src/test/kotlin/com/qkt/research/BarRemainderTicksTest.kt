package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarRemainderTicksTest {
    private fun bd(v: String) = BigDecimal(v)

    private val bar = Candle("X", bd("100"), bd("110"), bd("90"), bd("105"), bd("5"), 0L, 60_000L)

    @Test
    fun `the widest half-spread ignores one-sided quotes and is zero with no ticks`() {
        val oneSided = listOf(Tick("X", bd("100"), 1L, bid = bd("99")), Tick("X", bd("100"), 2L, ask = bd("101")))
        val quoted = Tick("X", bd("100"), 3L, bid = bd("99"), ask = bd("103"))

        assertThat(maxHalfSpread(null, emptyList())).isEqualByComparingTo("0")
        assertThat(maxHalfSpread(null, oneSided)).isEqualByComparingTo("0")
        assertThat(maxHalfSpread(quoted, oneSided)).isEqualByComparingTo("2")
    }

    @Test
    fun `the synthetic rest visits high before low when asked`() {
        val lowFirst = syntheticRest(bar).asSequence().map { it.price }.toList()
        val highFirst = syntheticRest(bar, highFirst = true).asSequence().map { it.price }.toList()

        assertThat(lowFirst).containsExactly(bd("90"), bd("110"), bd("105"))
        assertThat(highFirst).containsExactly(bd("110"), bd("90"), bd("105"))
    }

    @Test
    fun `an empty slice keeps nothing`() {
        assertThat(extremeRest(Tick("X", bd("100"), 0L), emptyList<Tick>().iterator(), bar).hasNext()).isFalse()
    }

    @Test
    fun `only new extremes are kept and the close carries what volume is left, never negative`() {
        val opening = Tick("X", bd("100"), 0L)
        val slice =
            listOf(
                Tick("X", bd("101"), 1L, volume = bd("4")),
                Tick("X", bd("100.5"), 2L, volume = bd("1")),
                Tick("X", bd("99"), 3L),
                Tick("X", bd("105"), 4L, volume = bd("1")),
            )

        val kept = extremeRest(opening, slice.iterator(), bar).asSequence().toList()

        assertThat(kept.map { it.timestamp }).containsExactly(1L, 3L, 4L)
        assertThat(kept.last().volume).isEqualByComparingTo("1")

        val heavy = listOf(Tick("X", bd("101"), 1L, volume = bd("9")), Tick("X", bd("105"), 2L))
        assertThat(extremeRest(opening, heavy.iterator(), bar).asSequence().last().volume).isEqualByComparingTo("0")
    }
}
