package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SvgChartTest {
    @Test
    fun `lineChart starts with svg tag and has viewBox`() {
        val pts = listOf(0L to BigDecimal("100"), 1L to BigDecimal("110"), 2L to BigDecimal("105"))
        val svg = SvgChart.lineChart(pts, width = 800, height = 400, title = "equity")
        assertThat(svg).startsWith("<svg")
        assertThat(svg).contains("viewBox=\"0 0 800 400\"")
        assertThat(svg).contains("equity")
    }

    @Test
    fun `lineChart with empty points still produces valid SVG`() {
        val svg = SvgChart.lineChart(emptyList(), width = 400, height = 200, title = "empty")
        assertThat(svg).startsWith("<svg")
        assertThat(svg).endsWith("</svg>")
    }

    @Test
    fun `lineChartWithUnderwater shades drawdown regions`() {
        val curve =
            listOf(
                EquitySample(0L, BigDecimal("100")),
                EquitySample(1L, BigDecimal("120")),
                EquitySample(2L, BigDecimal("108")),
                EquitySample(3L, BigDecimal("121")),
            )
        val dds =
            listOf(
                DrawdownPeriod(
                    peakTimestamp = 1L,
                    peakEquity = BigDecimal("120"),
                    troughTimestamp = 2L,
                    troughEquity = BigDecimal("108"),
                    recoveryTimestamp = 3L,
                    depthPct = BigDecimal("-0.10"),
                    durationMs = 2L,
                    ongoing = false,
                ),
            )
        val svg = SvgChart.lineChartWithUnderwater(curve, dds, width = 800, height = 400)
        assertThat(svg).contains("<rect")
    }

    @Test
    fun `fanChart renders bands and median`() {
        val fan =
            (0..9).map { i ->
                EquityFanPoint(
                    tradeIndex = i,
                    p5 = BigDecimal(i * 5),
                    p25 = BigDecimal(i * 7),
                    p50 = BigDecimal(i * 10),
                    p75 = BigDecimal(i * 13),
                    p95 = BigDecimal(i * 15),
                )
            }
        val svg = SvgChart.fanChart(fan, width = 800, height = 400)
        assertThat(svg).contains("<svg")
        assertThat(svg.split("<path").size - 1).isGreaterThanOrEqualTo(2)
    }
}
