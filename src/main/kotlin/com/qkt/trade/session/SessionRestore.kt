package com.qkt.trade.session

import com.qkt.execution.OrderRequest

/**
 * Recompiles every journaled intent, in order, on its identity without firing it, so what an earlier run
 * registered (exit hooks, ON FILL children) exists before a live session restores its persisted state.
 */
fun rebuildIdentities(
    journaled: List<JournaledIntent>,
    streams: BotSessionStreams,
    identities: Map<String, BotIntentStrategy>,
) {
    journaled.forEach { j ->
        val identity = requireNotNull(identities[j.identity]) { "journaled identity '${j.identity}' is not declared" }
        identity.rebuild(sessionIntent(j.source, streams).action)
    }
}

/**
 * Every order id inside [request]: the request, a timed exit's target, a bracket's entry, an OTO's parent
 * and children, an OCO's legs. A venue can answer for any of them (a rejected bracket entry under an
 * `EXIT AFTER`), so each is linked to the intent that placed the request.
 */
fun requestIds(request: OrderRequest): List<String> =
    listOf(request.id) +
        when (request) {
            is OrderRequest.TimeExit -> requestIds(request.target)
            is OrderRequest.Bracket -> requestIds(request.entry)
            is OrderRequest.OTO -> requestIds(request.parent) + request.children.flatMap(::requestIds)
            is OrderRequest.StandaloneOCO -> requestIds(request.leg1) + requestIds(request.leg2)
            else -> emptyList()
        }
