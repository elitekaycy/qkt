package com.qkt.backtest

import com.qkt.backtest.metrics.DRAWDOWN_PERIOD_THRESHOLD
import com.qkt.backtest.metrics.DrawdownAnalyzer
import com.qkt.backtest.metrics.sharpe
import com.qkt.risk.DrawdownTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #292 — locks the streaming [EquityMetrics] to the batch metric functions. Feeding a curve sample
 * by sample must produce the exact numbers a one-pass computation over the whole curve would, since
 * the collector relies on this to drop the full-resolution curve from memory without changing any
 * reported metric.
 */
class EquityMetricsTest {
    private val annualization = BigDecimal("252")

    /** A jagged curve with several peak-trough-recovery cycles plus an open drawdown at the end. */
    private fun fixtureCurve(): List<EquitySample> {
        val deltas =
            listOf(
                10,
                5,
                -3,
                -8,
                12,
                4,
                -20,
                -5,
                30,
                -7,
                -4,
                9,
                15,
                -25,
                6,
                -2,
                18,
                -11,
                -9,
                22,
                -30,
                14,
                7,
                -6,
                -1,
                19,
                -13,
                25,
                -40,
                8,
            )
        var equity = BigDecimal("1000")
        var ts = 0L
        val out = mutableListOf(EquitySample(ts, equity))
        for (d in deltas) {
            ts += 60_000L
            equity = equity.add(BigDecimal(d))
            out.add(EquitySample(ts, equity))
        }
        return out
    }

    private fun feed(curve: List<EquitySample>): EquityMetrics {
        val m = EquityMetrics()
        for (s in curve) m.accept(s.timestamp, s.equity)
        return m
    }

    @Test
    fun `max drawdown matches batch fromCurve`() {
        val curve = fixtureCurve()
        val m = feed(curve)
        assertThat(m.maxDrawdown())
            .isEqualByComparingTo(DrawdownTracker.fromCurve(curve.map { it.equity }))
    }

    @Test
    fun `sharpe matches batch sharpe`() {
        val curve = fixtureCurve()
        val m = feed(curve)
        val batch = sharpe(curve.map { it.equity }, annualization)
        assertThat(m.sharpe(annualization)).isEqualByComparingTo(batch!!)
    }

    @Test
    fun `drawdown periods match batch analyze exactly`() {
        val curve = fixtureCurve()
        val m = feed(curve)
        assertThat(m.drawdownPeriods())
            .isEqualTo(DrawdownAnalyzer.analyze(curve, DRAWDOWN_PERIOD_THRESHOLD))
    }

    @Test
    fun `starting equity count and timestamps track the stream`() {
        val curve = fixtureCurve()
        val m = feed(curve)
        assertThat(m.startingEquity()).isEqualByComparingTo(curve.first().equity)
        assertThat(m.count).isEqualTo(curve.size)
        assertThat(m.firstTimestamp()).isEqualTo(curve.first().timestamp)
        assertThat(m.lastTimestamp()).isEqualTo(curve.last().timestamp)
    }

    @Test
    fun `empty stream yields null sharpe zero drawdown no periods`() {
        val m = EquityMetrics()
        assertThat(m.sharpe(annualization)).isNull()
        assertThat(m.maxDrawdown()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(m.drawdownPeriods()).isEmpty()
        assertThat(m.count).isEqualTo(0)
        assertThat(m.firstTimestamp()).isNull()
    }

    @Test
    fun `metrics are readable mid-stream without disturbing later results`() {
        val curve = fixtureCurve()
        val m = EquityMetrics()
        // Read repeatedly while feeding — must not corrupt the running state.
        for (s in curve) {
            m.accept(s.timestamp, s.equity)
            m.maxDrawdown()
            m.drawdownPeriods()
            m.sharpe(annualization)
        }
        assertThat(m.maxDrawdown())
            .isEqualByComparingTo(DrawdownTracker.fromCurve(curve.map { it.equity }))
        assertThat(m.drawdownPeriods())
            .isEqualTo(DrawdownAnalyzer.analyze(curve, DRAWDOWN_PERIOD_THRESHOLD))
    }
}
