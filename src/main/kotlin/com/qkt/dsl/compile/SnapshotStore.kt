package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotKind
import java.math.BigDecimal

class SnapshotStore(
    maxRollingPerName: Map<String, Int>,
) {
    private val slots: MutableMap<Triple<String, String, SnapshotKind>, BigDecimal> = HashMap()
    private val rollingCapacity: Map<String, Int> = maxRollingPerName.mapValues { it.value + 1 }
    private val rolling: MutableMap<Pair<String, String>, ArrayDeque<BigDecimal?>> = HashMap()

    fun captureSlot(
        alias: String,
        name: String,
        kind: SnapshotKind,
        value: BigDecimal,
    ) {
        slots[Triple(alias, name, kind)] = value
    }

    fun readSlot(
        alias: String,
        name: String,
        kind: SnapshotKind,
    ): BigDecimal? = slots[Triple(alias, name, kind)]

    fun clearSlot(
        alias: String,
        name: String,
        kind: SnapshotKind,
    ) {
        slots.remove(Triple(alias, name, kind))
    }

    fun pushRolling(
        alias: String,
        name: String,
        value: BigDecimal?,
    ) {
        val cap = rollingCapacity[name] ?: return
        val key = alias to name
        val deque = rolling.getOrPut(key) { ArrayDeque(cap) }
        deque.addLast(value)
        while (deque.size > cap) deque.removeFirst()
    }

    fun readRolling(
        alias: String,
        name: String,
        offset: Int,
    ): BigDecimal? {
        val deque = rolling[alias to name] ?: return null
        if (offset < 0 || offset >= deque.size) return null
        return deque[deque.size - 1 - offset]
    }
}
