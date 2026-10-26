package com.qkt.dsl.compile

import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.marketdata.Candle

/**
 * Whether a hub field's newest value belongs to an older record than the alias's newest record.
 *
 * The hub publishes nothing for a field a record leaves unknown, so that field's stream keeps the
 * previous record's value. Read beside the newest record's other fields it would mix two records:
 * `cal.actual > cal.forecast` comparing last month's actual with this month's forecast. Every
 * expanded hub alias carries a record stream ([HubFieldExpansion.RECORD_FIELD]) that ticks for every
 * record, so a field candle that starts before the record candle is from an earlier record and the
 * read is undefined, as the hub's own contract says an unknown field is.
 */
internal object HubRecordCheck {
    fun isOlderThanRecord(
        alias: String,
        key: HubKey,
        candle: Candle,
        ctx: EvalContext,
    ): Boolean {
        if (!key.broker.equals(HUB_BROKER, ignoreCase = true)) return false
        val recordAlias = HubFieldExpansion.recordAliasOf(alias) ?: return false
        val recordKey = ctx.streams[recordAlias] ?: return false
        val record =
            if (ctx.currentAlias == recordAlias) {
                ctx.candle
            } else {
                ctx.historyAsOfMs?.let { ctx.hub.latestAtOrBefore(recordKey, it) } ?: ctx.hub.latest(recordKey)
            } ?: return false
        return candle.startTime < record.startTime
    }
}
