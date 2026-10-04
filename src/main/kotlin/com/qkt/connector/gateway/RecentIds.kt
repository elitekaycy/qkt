package com.qkt.connector.gateway

/** The last [capacity] ids added, oldest dropped first. */
internal class RecentIds(
    private val capacity: Int,
) {
    private val ids =
        object : LinkedHashMap<String, Unit>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
        }

    /** Adds [id]; false when it was already among the recent ones. */
    fun add(id: String): Boolean = ids.put(id, Unit) == null

    /** Whether [id] is among the recent ones. */
    fun contains(id: String): Boolean = ids.containsKey(id)
}
