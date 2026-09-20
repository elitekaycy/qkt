package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SeriesSymbols

/**
 * Stream aliases no order may target. Macro series (MACRO:) are read-only — they carry a
 * published statistic, not a tradeable price (#440). A hub dataset alias was expanded away, so
 * it is refused by name; its hidden per-field streams are refused by broker.
 */
internal fun readOnlyAliases(
    streams: Map<String, HubKey>,
    datasetAliases: Set<String>,
): Set<String> =
    streams
        .filterValues {
            it.broker == "MACRO" ||
                it.broker == SeriesSymbols.BROKER ||
                it.broker.equals(HUB_BROKER, ignoreCase = true)
        }.keys + datasetAliases

/** Rejects, at compile time, any order action (including nested ON_FILL and exit hooks) on a read-only alias. */
internal fun rejectReadOnlyOrders(
    action: ActionAst,
    readOnlyAliases: Set<String>,
) {
    fun reject(stream: String) =
        require(stream !in readOnlyAliases) {
            "Series '$stream' is read-only — it has no tradeable price; remove the order " +
                "action targeting it (BUY/SELL/CLOSE/CANCEL)."
        }
    when (action) {
        is Buy -> {
            reject(action.stream)
            rejectNestedOrders(action.opts, readOnlyAliases)
        }
        is Sell -> {
            reject(action.stream)
            rejectNestedOrders(action.opts, readOnlyAliases)
        }
        is Close -> reject(action.stream)
        is Resize -> reject(action.stream)
        is Cancel -> reject(action.stream)
        is Latch -> {
            reject(action.stream)
            action.entries.mapNotNull { it.stream }.forEach(::reject)
        }
        is OcoEntry -> {
            rejectReadOnlyOrders(action.leg1, readOnlyAliases)
            rejectReadOnlyOrders(action.leg2, readOnlyAliases)
        }
        is Block -> action.actions.forEach { rejectReadOnlyOrders(it, readOnlyAliases) }
        CloseAll, CancelAll, is Log -> Unit
    }
}

private fun rejectNestedOrders(
    opts: ActionOpts,
    readOnlyAliases: Set<String>,
) {
    opts.onFill.forEach { rejectReadOnlyOrders(it, readOnlyAliases) }
    (opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose)
        .forEach { rejectReadOnlyOrders(it, readOnlyAliases) }
}
