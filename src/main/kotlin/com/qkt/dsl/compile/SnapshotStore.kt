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
        symbol: String,
        name: String,
        kind: SnapshotKind,
        value: BigDecimal,
    ) {
        slots[Triple(symbol, name, kind)] = value
    }

    fun readSlot(
        symbol: String,
        name: String,
        kind: SnapshotKind,
    ): BigDecimal? = slots[Triple(symbol, name, kind)]

    fun clearSlot(
        symbol: String,
        name: String,
        kind: SnapshotKind,
    ) {
        slots.remove(Triple(symbol, name, kind))
    }

    fun pushRolling(
        symbol: String,
        name: String,
        value: BigDecimal?,
    ) {
        val cap = rollingCapacity[name] ?: return
        val key = symbol to name
        val deque = rolling.getOrPut(key) { ArrayDeque(cap) }
        deque.addLast(value)
        while (deque.size > cap) deque.removeFirst()
    }

    fun readRolling(
        symbol: String,
        name: String,
        offset: Int,
    ): BigDecimal? {
        val deque = rolling[symbol to name] ?: return null
        if (offset < 0 || offset >= deque.size) return null
        return deque[deque.size - 1 - offset]
    }
}
