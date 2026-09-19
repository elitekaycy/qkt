package com.qkt.parity

import com.qkt.parity.ScalpStackCases.Case

/** Price ramps, entry tapes and expected-side builders for the scalping stack parity cases. */
internal object ScalpStackTapes {
    /** A price walk from `from` to `to` inclusive, one point per step -- one candle per level. */
    fun ramp(
        from: Int,
        to: Int,
    ): List<String> = if (from <= to) (from..to).map { it.toString() } else (from downTo to).map { it.toString() }

    /** The entry tape: the rule fires on the first `100`, the seed fills on the second. */
    fun entry(vararg then: String): List<String> = listOf("100", "100") + then

    fun buys(n: Int) = List(n) { "BUY" }

    fun sells(n: Int) = List(n) { "SELL" }

    fun single(
        id: String,
        action: String,
        prices: List<String>,
        expectedSides: List<String>,
        endsFlat: Boolean = false,
    ) = Case(
        id = id,
        prices = prices,
        expectedSides = expectedSides,
        endsFlat = endsFlat,
        source =
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN ${action.prependIndent("  ").trimStart()}
            """.trimIndent(),
    )
}
