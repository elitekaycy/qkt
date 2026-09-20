package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.EquitySample
import com.qkt.backtest.report.SvgChartFrame.PADDING_BOTTOM
import com.qkt.backtest.report.SvgChartFrame.PADDING_TOP
import com.qkt.backtest.report.SvgChartFrame.appendAxes
import com.qkt.backtest.report.SvgChartFrame.emptySvg
import com.qkt.backtest.report.SvgChartFrame.scaleX
import com.qkt.backtest.report.SvgChartFrame.scaleY
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/**
 * Inline-SVG chart primitives used by [HtmlReportWriter]. Every function returns a
 * complete `<svg>` element as a string with no external dependencies (no CSS file,
 * no JS, no font links) so charts render identically offline and in plain email.
 *
 * Layouts are fixed-size with hard-coded padding — backtest reports don't need
 * responsive sizing. If a chart shape needs to vary at runtime, render at the
 * largest expected size and let CSS scale the SVG element.
 */
object SvgChart {
    /**
     * Render a simple equity-style line chart. [points] are (timestampMs, value)
     * pairs in chronological order; empty input renders an empty-state placeholder.
     */
    fun lineChart(
        points: List<Pair<Long, BigDecimal>>,
        width: Int,
        height: Int,
        title: String,
    ): String {
        if (points.isEmpty()) return emptySvg(width, height, title)
        val xs = points.map { it.first }
        val ys = points.map { it.second.toDouble() }
        val xMin = xs.min().toDouble()
        val xMax = xs.max().toDouble()
        val yMin = ys.min()
        val yMax = ys.max()
        val poly =
            points.joinToString(" ") { (t, e) ->
                "${scaleX(t.toDouble(), xMin, xMax, width)},${scaleY(e.toDouble(), yMin, yMax, height)}"
            }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">")
            append("<title>${htmlEscape(title)}</title>")
            appendAxes(width, height, yMin, yMax)
            append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"1.5\" points=\"$poly\"/>")
            append("</svg>")
        }
    }

    /**
     * Equity curve with drawdown periods overlaid as red translucent rectangles.
     * Open drawdowns (no recovery timestamp) extend to the right edge.
     */
    fun lineChartWithUnderwater(
        curve: List<EquitySample>,
        drawdowns: List<DrawdownPeriod>,
        width: Int,
        height: Int,
    ): String {
        if (curve.isEmpty()) return emptySvg(width, height, "equity")
        val xMin = curve.first().timestamp.toDouble()
        val xMax = curve.last().timestamp.toDouble()
        val yMin = curve.minOf { it.equity }.toDouble()
        val yMax = curve.maxOf { it.equity }.toDouble()
        val poly =
            curve.joinToString(" ") { s ->
                "${scaleX(s.timestamp.toDouble(), xMin, xMax, width)}," +
                    "${scaleY(s.equity.toDouble(), yMin, yMax, height)}"
            }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">")
            append("<title>equity with drawdowns</title>")
            for (dd in drawdowns) {
                val x0 = scaleX(dd.peakTimestamp.toDouble().coerceIn(xMin, xMax), xMin, xMax, width)
                val recovery = dd.recoveryTimestamp?.toDouble() ?: xMax
                val x1 = scaleX(recovery.coerceIn(xMin, xMax), xMin, xMax, width)
                val left = min(x0, x1)
                val rectWidth = max(x0, x1) - left
                append(
                    "<rect x=\"$left\" y=\"$PADDING_TOP\" width=\"$rectWidth\" " +
                        "height=\"${height - PADDING_TOP - PADDING_BOTTOM}\" fill=\"#ff7f7f\" opacity=\"0.15\"/>",
                )
            }
            appendAxes(width, height, yMin, yMax)
            append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"1.5\" points=\"$poly\"/>")
            append("</svg>")
        }
    }

    /**
     * Render a Monte-Carlo bootstrap "fan" — the median line plus a shaded p5/p95
     * envelope. [fan] is one [EquityFanPoint] per terminal-equity bucket, sorted by
     * the bucket index (timestamp is implicit from position).
     */
    fun fanChart(
        fan: List<EquityFanPoint>,
        width: Int,
        height: Int,
    ): String {
        if (fan.isEmpty()) return emptySvg(width, height, "monte-carlo")
        val xMin = 0.0
        val xMax = (fan.size - 1).toDouble().coerceAtLeast(1.0)
        val yMin = fan.minOf { it.p5 }.toDouble()
        val yMax = fan.maxOf { it.p95 }.toDouble()

        fun pathBetween(
            upper: List<BigDecimal>,
            lower: List<BigDecimal>,
        ): String {
            val sb = StringBuilder("M")
            for ((i, v) in upper.withIndex()) {
                sb.append(scaleX(i.toDouble(), xMin, xMax, width))
                sb.append(',')
                sb.append(scaleY(v.toDouble(), yMin, yMax, height))
                sb.append(' ')
            }
            sb.append("L")
            for ((i, v) in lower.withIndex().reversed()) {
                sb.append(scaleX(i.toDouble(), xMin, xMax, width))
                sb.append(',')
                sb.append(scaleY(v.toDouble(), yMin, yMax, height))
                sb.append(' ')
            }
            sb.append("Z")
            return sb.toString()
        }

        val outer = pathBetween(fan.map { it.p95 }, fan.map { it.p5 })
        val inner = pathBetween(fan.map { it.p75 }, fan.map { it.p25 })
        val median =
            fan.joinToString(" ") { p ->
                "${scaleX(p.tradeIndex.toDouble(), xMin, xMax, width)}," +
                    "${scaleY(p.p50.toDouble(), yMin, yMax, height)}"
            }

        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">")
            append("<title>monte-carlo fan</title>")
            appendAxes(width, height, yMin, yMax)
            append("<path d=\"$outer\" fill=\"#1f77b4\" opacity=\"0.15\"/>")
            append("<path d=\"$inner\" fill=\"#1f77b4\" opacity=\"0.30\"/>")
            append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"1.5\" points=\"$median\"/>")
            append("</svg>")
        }
    }
}
