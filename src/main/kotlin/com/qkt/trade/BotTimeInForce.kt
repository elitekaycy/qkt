package com.qkt.trade

import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.TifAst
import com.qkt.execution.TimeInForce
import com.qkt.execution.dayExpiryMs

/** A one-shot order's time in force and the absolute expiry the venue receives, if any. */
internal fun resolveTif(
    tif: TifAst?,
    symbol: String,
    timestamp: Long,
    calendar: TradingCalendar,
): Pair<TimeInForce, Long?> =
    when (tif) {
        null, is Gtc -> TimeInForce.GTC to null
        // The MT5 wire carries expiry only as an absolute deadline, so DAY becomes the end of the symbol's session,
        // the same expiry a strategy's DAY order carries ([dayExpiryMs]).
        is Day -> TimeInForce.DAY to dayExpiryMs(calendar, symbol, timestamp)
        is Gtd -> TimeInForce.GTD to literal(tif.until, "TIF GTD").toLong()
        else -> error("TIF ${tif::class.simpleName} is not supported one-shot; use GTC, DAY, or GTD")
    }
