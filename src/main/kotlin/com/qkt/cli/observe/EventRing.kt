package com.qkt.cli.observe

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonObject

class EventRing(
    private val capacity: Int = 1000,
) {
    private val buf: ArrayDeque<EventEntry> = ArrayDeque(capacity)
    private val listeners: CopyOnWriteArrayList<(EventEntry) -> Unit> = CopyOnWriteArrayList()
    private val lock = ReentrantLock()

    init {
        require(capacity >= 1) { "EventRing capacity must be >= 1: $capacity" }
    }

    fun append(
        kind: String,
        payload: JsonObject,
    ) {
        val entry = EventEntry(System.currentTimeMillis(), kind, payload)
        lock.withLock {
            buf.addLast(entry)
            while (buf.size > capacity) buf.removeFirst()
        }
        for (l in listeners) runCatching { l(entry) }
    }

    fun snapshot(
        since: Long,
        limit: Int,
    ): List<EventEntry> {
        require(limit >= 1) { "limit must be >= 1: $limit" }
        return lock.withLock {
            buf.filter { it.ts >= since }.takeLast(limit.coerceAtMost(capacity))
        }
    }

    fun subscribe(listener: (EventEntry) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun size(): Int = lock.withLock { buf.size }
}
