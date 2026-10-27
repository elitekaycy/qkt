package com.qkt.trade

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.TifAst
import com.qkt.execution.TimeInForce

/** A one-shot order's time in force and the absolute expiry the venue receives, if any. */
internal fun resolveTif(
    tif: TifAst?,
    timestamp: Long,
): Pair<TimeInForce, Long?> =
    when (tif) {
        null, is Gtc -> TimeInForce.GTC to null
        // The MT5 wire carries expiry only as an absolute deadline, so DAY becomes
        // an explicit end-of-UTC-day expiration on pending entries.
        is Day -> TimeInForce.DAY to (Math.floorDiv(timestamp, MS_PER_DAY) + 1) * MS_PER_DAY
        is Gtd -> TimeInForce.GTD to literal(tif.until, "TIF GTD").toLong()
        else -> error("TIF ${tif::class.simpleName} is not supported one-shot; use GTC, DAY, or GTD")
    }

private const val MS_PER_DAY: Long = 86_400_000L
