package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CHAIN_BROKER
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OPTIONS_BROKER
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
                it.broker.equals(HUB_BROKER, ignoreCase = true) ||
                it.broker.equals(CHAIN_BROKER, ignoreCase = true) ||
                it.broker.equals(OPTIONS_BROKER, ignoreCase = true)
        }.keys + datasetAliases

/** Rejects, at compile time, any order action (including nested ON_FILL and exit hooks) on a read-only alias. */
internal fun rejectReadOnlyOrders(
    action: ActionAst,
    readOnlyAliases: Set<String>,
) {
    orderTargets(action).firstOrNull { it in readOnlyAliases }?.let { stream ->
        throw IllegalArgumentException(
            "Series '$stream' is read-only — it has no tradeable price; remove the order " +
                "action targeting it (BUY/SELL/CLOSE/CANCEL).",
        )
    }
}

/**
 * Every alias an order action targets, nested ON_FILL and exit hooks included, in the order they are
 * written. A structure orders contracts of its option root, never a declared alias.
 */
internal fun orderTargets(action: ActionAst): List<String> =
    when (action) {
        is Buy -> listOf(action.stream) + nestedTargets(action.opts)
        is Sell -> listOf(action.stream) + nestedTargets(action.opts)
        is Close -> listOf(action.stream)
        is Resize -> listOf(action.stream)
        is Cancel -> listOf(action.stream)
        is Latch -> listOf(action.stream) + action.entries.mapNotNull { it.stream }
        is OcoEntry -> orderTargets(action.leg1) + orderTargets(action.leg2)
        is Block -> action.actions.flatMap(::orderTargets)
        CloseAll, CancelAll, is Log, is com.qkt.dsl.ast.OpenStructure -> emptyList()
    }

private fun nestedTargets(opts: ActionOpts): List<String> =
    (opts.onFill + opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose).flatMap(::orderTargets)
