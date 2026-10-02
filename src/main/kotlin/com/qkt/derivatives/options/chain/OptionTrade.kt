package com.qkt.derivatives.options.chain

import java.math.BigDecimal

/**
 * One venue trade of an option, as far as a chain needs it: the venue's [tradeId] (for dedupe), its
 * [timestampMs], the instrument's [tradeSeq] (orders trades of one contract within a millisecond),
 * the [contract] code without venue, and the venue's [markPrice], mark implied volatility [iv]
 * (percent) and underlying [indexPrice] at the trade.
 */
data class OptionTrade(
    val tradeId: String,
    val timestampMs: Long,
    val tradeSeq: Long,
    val contract: String,
    val markPrice: BigDecimal,
    val iv: BigDecimal?,
    val indexPrice: BigDecimal,
)
