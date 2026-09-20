package com.qkt.app.order

/**
 * One-cancels-other linkage: for an order id, the orders to cancel once it executes. Covers
 * OCO legs, a bracket's stop/target pair and a stack layer's exits. Keys are the ids broker
 * events arrive under, which for a bracket leg is its entry's id.
 */
internal class SiblingLinks {
    private val byId: MutableMap<String, List<String>> = mutableMapOf()

    /** Every link, for persistence. */
    val all: Map<String, List<String>> get() = byId

    /** Orders linked to [id]; empty when it has none. */
    operator fun get(id: String): List<String> = byId[id].orEmpty()

    operator fun set(
        id: String,
        siblings: List<String>,
    ) {
        byId[id] = siblings
    }

    /** Links [first] and [second] to cancel each other. */
    fun pair(
        first: String,
        second: String,
    ) {
        byId[first] = listOf(second)
        byId[second] = listOf(first)
    }

    fun remove(id: String) {
        byId.remove(id)
    }
}
