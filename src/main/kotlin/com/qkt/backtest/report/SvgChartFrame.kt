package com.qkt.backtest.report

import java.util.Locale

/**
 * The fixed plot frame shared by every [SvgChart]: padding around the plot area, the mapping from
 * data coordinates to SVG pixels, the two axis lines with their min/max labels, and the empty-state
 * placeholder.
 */
internal object SvgChartFrame {
    const val PADDING_LEFT = 60
    const val PADDING_BOTTOM = 30
    const val PADDING_TOP = 20
    const val PADDING_RIGHT = 20

    fun emptySvg(
        width: Int,
        height: Int,
        title: String,
    ): String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">" +
            "<title>${htmlEscape(title)}</title><text x=\"$PADDING_LEFT\" y=\"${height / 2}\">no data</text></svg>"

    fun StringBuilder.appendAxes(
        width: Int,
        height: Int,
        yMin: Double,
        yMax: Double,
    ) {
        append(
            "<line x1=\"$PADDING_LEFT\" y1=\"${height - PADDING_BOTTOM}\" " +
                "x2=\"${width - PADDING_RIGHT}\" y2=\"${height - PADDING_BOTTOM}\" stroke=\"#888\"/>",
        )
        append(
            "<line x1=\"$PADDING_LEFT\" y1=\"$PADDING_TOP\" " +
                "x2=\"$PADDING_LEFT\" y2=\"${height - PADDING_BOTTOM}\" stroke=\"#888\"/>",
        )
        append(
            "<text x=\"5\" y=\"${PADDING_TOP + 5}\" font-size=\"10\">${axisLabel(yMax)}</text>",
        )
        append(
            "<text x=\"5\" y=\"${height - PADDING_BOTTOM}\" font-size=\"10\">${axisLabel(yMin)}</text>",
        )
    }

    fun scaleX(
        v: Double,
        min: Double,
        max: Double,
        width: Int,
    ): Double {
        if (max == min) return PADDING_LEFT.toDouble()
        val range = (width - PADDING_LEFT - PADDING_RIGHT)
        return PADDING_LEFT + (v - min) / (max - min) * range
    }

    fun scaleY(
        v: Double,
        min: Double,
        max: Double,
        height: Int,
    ): Double {
        if (max == min) return PADDING_TOP + (height - PADDING_TOP - PADDING_BOTTOM) / 2.0
        val range = (height - PADDING_TOP - PADDING_BOTTOM)
        return PADDING_TOP + (max - v) / (max - min) * range
    }

    private fun axisLabel(value: Double): String = String.format(Locale.US, "%.4g", value)
}
