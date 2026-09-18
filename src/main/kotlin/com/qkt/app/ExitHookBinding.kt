package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.execution.OrderRequest
import com.qkt.persistence.PersistedExitHookBinding
import java.math.BigDecimal

/**
 * One registered exit hook: the hook-bearing entry's order ids, the venue tickets its fills
 * opened, and the quantity and PnL its exits have accumulated so far. [fired] turns true once the
 * hook dispatched; a fired binding is never persisted.
 */
internal data class ExitHookBinding(
    val id: String,
    val strategyId: String,
    val symbol: String,
    val entrySide: Side,
    val ref: ExitHookRef,
    val entryOrderIds: Set<String>,
    val stopOrderIds: Set<String>,
    val takeProfitOrderIds: Set<String>,
    val closeOrderIds: MutableSet<String> = linkedSetOf(),
    val brokerTickets: MutableSet<String> = linkedSetOf(),
    var activeQuantity: BigDecimal = BigDecimal.ZERO,
    var exitQuantity: BigDecimal = BigDecimal.ZERO,
    var exitPnl: BigDecimal = BigDecimal.ZERO,
    var fired: Boolean = false,
)

/** The order ids a hook-bearing request will fill under, by role. */
internal data class ExitHookRequestIds(
    val entries: Set<String>,
    val stops: Set<String>,
    val takeProfits: Set<String>,
)

/** The entry, stop and take-profit order ids [request]'s fills will carry. */
internal fun exitHookRequestIds(request: OrderRequest): ExitHookRequestIds =
    when (request) {
        is OrderRequest.Bracket ->
            ExitHookRequestIds(
                // Attached/fallback paths key fills under entry.id; a venue with
                // native BRACKET but no position-modify support may retain parent id.
                entries = setOf(request.id, request.entry.id),
                stops = setOf("${request.id}-sl"),
                takeProfits = setOf("${request.id}-tp"),
            )
        is OrderRequest.Stack -> {
            val entries = request.plan.layers.mapTo(linkedSetOf()) { "${request.id}-l${it.index}" }
            ExitHookRequestIds(
                entries = entries,
                stops = entries.mapTo(linkedSetOf()) { "$it-sl" },
                takeProfits = entries.mapTo(linkedSetOf()) { "$it-tp" },
            )
        }
        is OrderRequest.StandaloneOCO, is OrderRequest.OTO, is OrderRequest.ScaleOut, is OrderRequest.TimeExit ->
            error("Exit hooks do not support ${request::class.simpleName} parents in v1")
        else -> ExitHookRequestIds(setOf(request.id), emptySet(), emptySet())
    }

/** Rebuild a binding saved before a restart, under the already validated [ref]. */
internal fun PersistedExitHookBinding.toBinding(ref: ExitHookRef): ExitHookBinding =
    ExitHookBinding(
        id = bindingId,
        strategyId = strategyId,
        symbol = symbol,
        entrySide = entrySide,
        ref = ref,
        entryOrderIds = entryOrderIds.toSet(),
        stopOrderIds = stopOrderIds.toSet(),
        takeProfitOrderIds = takeProfitOrderIds.toSet(),
        closeOrderIds = closeOrderIds.toMutableSet(),
        brokerTickets = brokerTickets.toMutableSet(),
        activeQuantity = activeQuantity,
        exitQuantity = exitQuantity,
        exitPnl = exitPnl,
    )

/** The durable form of this binding. */
internal fun ExitHookBinding.toPersisted(): PersistedExitHookBinding =
    PersistedExitHookBinding(
        bindingId = id,
        strategyId = strategyId,
        symbol = symbol,
        entrySide = entrySide,
        definitionId = ref.definitionId,
        fingerprint = ref.fingerprint,
        entryOrderIds = entryOrderIds.toList(),
        stopOrderIds = stopOrderIds.toList(),
        takeProfitOrderIds = takeProfitOrderIds.toList(),
        closeOrderIds = closeOrderIds.toList(),
        brokerTickets = brokerTickets.toList(),
        activeQuantity = activeQuantity,
        exitQuantity = exitQuantity,
        exitPnl = exitPnl,
    )
