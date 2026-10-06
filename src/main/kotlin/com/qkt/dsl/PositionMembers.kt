package com.qkt.dsl

import com.qkt.dsl.ast.StateSource

/** The `POSITION.<alias>.<member>` table [DslVocabulary.positionAccessors] publishes, kept apart for its size. */
internal object PositionMembers {
    /** Each member spelling and what it compiles to: null is the signed net quantity, any other a [StateSource] read. */
    val accessors: Map<String, StateSource?> =
        linkedMapOf(
            "quantity" to null,
            "qty" to null,
            "entry_price" to StateSource.POSITION_AVG_PRICE,
            "avg_price" to StateSource.POSITION_AVG_PRICE,
            "avg_entry_price" to StateSource.POSITION_AVG_PRICE,
            "pnl" to StateSource.POSITION_PNL,
            "realized_pnl" to StateSource.POSITION_REALIZED_PNL,
            "unrealized_pnl" to StateSource.POSITION_UNREALIZED_PNL,
            "holding_duration" to StateSource.POSITION_HOLDING_DURATION,
            "mfe" to StateSource.POSITION_MFE,
            "mae" to StateSource.POSITION_MAE,
            "count" to StateSource.POSITION_OPEN_COUNT,
            "open_count" to StateSource.POSITION_OPEN_COUNT,
            "longs" to StateSource.POSITION_LONG_COUNT,
            "long_count" to StateSource.POSITION_LONG_COUNT,
            "shorts" to StateSource.POSITION_SHORT_COUNT,
            "short_count" to StateSource.POSITION_SHORT_COUNT,
            "gross" to StateSource.POSITION_GROSS,
            "trades_today" to StateSource.POSITION_TRADES_TODAY,
            "last_trade_at" to StateSource.POSITION_LAST_TRADE_AT,
            "delta" to StateSource.STRUCTURE_DELTA,
            "gamma" to StateSource.STRUCTURE_GAMMA,
            "vega" to StateSource.STRUCTURE_VEGA,
            "theta" to StateSource.STRUCTURE_THETA,
            "dte" to StateSource.STRUCTURE_DTE,
            "credit" to StateSource.STRUCTURE_CREDIT,
            "max_loss" to StateSource.STRUCTURE_MAX_LOSS,
            "pnl_pct" to StateSource.STRUCTURE_PNL_PCT,
        )
}
