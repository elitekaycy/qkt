package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/**
 * The client order id the gateway sees for an engine order: the engine's id and its submit time in
 * base 36 (`dsl-s--4` submitted at 1790835377133 is `dsl-s--4.mg8q2kv1`). A gateway remembers every id
 * for ever (idempotent submits, ids written off as dead), while an engine may hand an id out again
 * after a restart once its order has ended; the submit time makes every order's id new, and a restored
 * order's id is rebuilt from its persisted request. The engine's id is everything before the last `.`.
 *
 * An engine id too long for [MAX_LENGTH] is sent [shortened]: its first characters (so `dsl-<strategy>-` still
 * names its strategy) and a hash of the whole id, the same for the same id after a restart. Each shortened id
 * met in this process, sent, restored or booked, is remembered, so [engineId] maps it back (#1643).
 */
internal object GatewayClientIds {
    /**
     * The longest client order id qkt sends a gateway: Bybit's `orderLinkId` limit, the strictest venue behind
     * VGP v1 (which itself carries 64). `/v1/health` declares no limit, so every gateway id stays within this one.
     */
    const val MAX_LENGTH = 45

    // The submit-time suffix: `.` and eight base-36 digits, enough for any epoch millisecond until 2059.
    private const val STAMP_ROOM = 9
    private const val ENGINE_ROOM = MAX_LENGTH - STAMP_ROOM
    private const val HASH_LENGTH = 10
    private const val REMEMBERED = 50_000

    private val engineIds: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > REMEMBERED
            },
        )

    /** [request]'s client order id at the gateway. */
    fun of(request: OrderRequest): String = "${remember(request.id)}.${request.timestamp.toString(36)}"

    /**
     * What of engine id [id] the gateway sees: [id] itself when it fits, else its head, `-` and a 10-digit base-36
     * hash of it (`dsl-soak_o13_bybit_fund--0-exit-close-r945` is `dsl-soak_o13_bybit_fund---` and the hash).
     */
    fun shortened(id: String): String {
        if (id.length <= ENGINE_ROOM) return id
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        // 51 bits: at most 10 base-36 digits.
        val bits = ByteBuffer.wrap(digest).long ushr 13
        val hash = bits.toString(36).padStart(HASH_LENGTH, '0')
        return "${id.take(ENGINE_ROOM - HASH_LENGTH - 1)}-$hash"
    }

    /** Notes engine id [id] so its [shortened] form maps back to it; returns that form. */
    fun remember(id: String): String {
        val short = shortened(id)
        if (short != id) engineIds[short] = id
        return short
    }

    /** The engine order id behind gateway id [clientOrderId]. */
    fun engineId(clientOrderId: String): String {
        val part = clientOrderId.substringBeforeLast('.')
        return engineIds[part] ?: part
    }

    /** [event] carrying the engine's order id instead of the gateway's. */
    fun toEngine(event: BrokerEvent.OrderEvent): BrokerEvent.OrderEvent {
        val id = engineId(event.clientOrderId)
        return when (event) {
            is BrokerEvent.OrderAccepted -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderCancelled -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderRejected -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderPartiallyFilled -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderFilled -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderCancelFailed -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderModified -> event.copy(clientOrderId = id)
        }
    }
}
