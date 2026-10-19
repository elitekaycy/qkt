package com.qkt.trade.session

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
