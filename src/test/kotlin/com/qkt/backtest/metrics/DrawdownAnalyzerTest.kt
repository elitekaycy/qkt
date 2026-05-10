package com.qkt.backtest.metrics

import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class DrawdownAnalyzerTest {
    private fun sample(
        t: Long,
        e: String,
    ) = EquitySample(t, BigDecimal(e))

    private val threshold = BigDecimal("-0.01")

    @Test
    fun `empty curve yields empty list`() {
        assertThat(DrawdownAnalyzer.analyze(emptyList(), threshold)).isEmpty()
    }

    @Test
    fun `monotone-up curve yields empty list`() {
        val curve = listOf(sample(0, "100"), sample(1, "110"), sample(2, "120"))
        assertThat(DrawdownAnalyzer.analyze(curve, threshold)).isEmpty()
    }

    @Test
    fun `single peak-trough-recovery cycle is captured`() {
        val curve =
            listOf(
                sample(0, "100"),
                sample(1, "120"),
                sample(2, "108"),
                sample(3, "120"),
                sample(4, "125"),
            )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(1)
        val p = periods[0]
        assertThat(p.peakTimestamp).isEqualTo(1L)
        assertThat(p.peakEquity).isEqualByComparingTo("120")
        assertThat(p.troughTimestamp).isEqualTo(2L)
        assertThat(p.troughEquity).isEqualByComparingTo("108")
        assertThat(p.recoveryTimestamp).isEqualTo(3L)
        assertThat(p.depthPct.toDouble()).isCloseTo(-0.10, within(0.001))
        assertThat(p.durationMs).isEqualTo(2L)
        assertThat(p.ongoing).isFalse
    }

    @Test
    fun `multiple non-overlapping cycles are captured`() {
        val curve =
            listOf(
                sample(0, "100"),
                sample(1, "110"),
                sample(2, "100"),
                sample(3, "110"),
                sample(4, "120"),
                sample(5, "108"),
                sample(6, "121"),
            )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(2)
    }

    @Test
    fun `open drawdown at end is marked ongoing`() {
        val curve =
            listOf(
                sample(0, "100"),
                sample(1, "120"),
                sample(2, "100"),
                sample(3, "105"),
            )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(1)
        assertThat(periods[0].ongoing).isTrue
        assertThat(periods[0].recoveryTimestamp).isNull()
    }

    @Test
    fun `drawdowns shallower than threshold are filtered`() {
        val curve =
            listOf(
                sample(0, "100"),
                sample(1, "100.5"),
                sample(2, "100.0"),
                sample(3, "100.5"),
            )
        assertThat(DrawdownAnalyzer.analyze(curve, threshold)).isEmpty()
    }

    @Test
    fun `output is sorted by depth ascending most negative first`() {
        val curve =
            listOf(
                sample(0, "100"),
                sample(1, "110"), sample(2, "108"), sample(3, "110"),
                sample(4, "120"), sample(5, "108"), sample(6, "121"),
                sample(7, "130"), sample(8, "126"), sample(9, "131"),
            )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(3)
        assertThat(periods[0].depthPct).isLessThan(periods[1].depthPct)
        assertThat(periods[1].depthPct).isLessThan(periods[2].depthPct)
    }
}
