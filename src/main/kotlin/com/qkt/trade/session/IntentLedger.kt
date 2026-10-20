package com.qkt.trade.session

import com.qkt.strategy.OrderOutcome
import java.math.BigDecimal

/** Something that happened to a session's orders, for the client's next read. */
data class SessionEvent(
    val seq: Long,
    /** `fill`, `rejected` or `cancelled`. */
    val kind: String,
    val identity: String,
    /** The intent the order came from; null for an exit the engine placed (a stop, a target, a timed close). */
    val intentId: String?,
    val orderId: String,
    val symbol: String?,
    val side: String?,
    val price: BigDecimal?,
    val quantity: BigDecimal?,
    /** `STOP`, `TAKE_PROFIT`, `CLOSE`, ... when the fill closed a position. */
    val exitReason: String?,
    val reason: String?,
    val atMs: Long,
)

/** A working entry order an intent placed. */
data class WorkingIntentOrder(
    val intentId: String,
    val identity: String,
    val orderId: String,
)

/**
 * The record of every intent in a session: its fate as it changes (queued, fired or skipped, then
 * filled, rejected or cancelled), the orders it placed, and the events the client has not read yet.
 * Shared by every identity of a session; written from the engine thread, read from the server's.
 */
class IntentLedger {
    private val fates = LinkedHashMap<String, IntentFate>()
    private val intentOfOrder = HashMap<String, String>()
    private val ended = HashSet<String>()
    private val unread = ArrayList<SessionEvent>()
    private var seq = 0L

    /** Told every fate as it changes (the session's journal). */
    @Volatile var onChange: (IntentFate) -> Unit = {}

    /**
     * Records [fate]. A fate never moves back: the pipeline may link, fill or reject an order while the
     * intent is still firing, so a later `fired` keeps the outcome already known and adds its order ids.
     */
    @Synchronized
    fun fate(fate: IntentFate) {
        val known = fates[fate.intentId]
        val compiled = fate.compiled || known?.compiled == true
        val tickMs = known?.tickMs ?: fate.tickMs
        fates[fate.intentId] =
            if (known == null || rank(fate.state) >= rank(known.state)) {
                fate.copy(
                    orderIds = ((known?.orderIds ?: emptyList()) + fate.orderIds).distinct(),
                    compiled = compiled,
                    tickMs = tickMs,
                )
            } else {
                known.copy(orderIds = (known.orderIds + fate.orderIds).distinct(), compiled = compiled, tickMs = tickMs)
            }
        fates[fate.intentId]?.takeIf { it != known }?.let(onChange)
    }

    private fun rank(state: String): Int =
        when (state) {
            "queued" -> 0
            "fired", "skipped" -> 1
            else -> 2
        }

    /** The engine compiled [intentId]'s action; recorded before it emits anything. */
    @Synchronized
    fun markCompiled(intentId: String) {
        val known = fates[intentId] ?: return
        if (known.compiled) return
        fates[intentId] = known.copy(compiled = true)
        onChange(fates.getValue(intentId))
    }

    /** [orderId] was submitted for [intentId]. */
    @Synchronized
    fun link(
        intentId: String,
        orderId: String,
    ) {
        intentOfOrder[orderId] = intentId
        fates[intentId]?.let { fates[intentId] = it.copy(orderIds = (it.orderIds + orderId).distinct()) }
    }

    @Synchronized
    fun outcome(
        identity: String,
        outcome: OrderOutcome,
    ) {
        val parent = (outcome as? OrderOutcome.Filled)?.parentOrderId
        val intentId =
            intentOfOrder[outcome.orderId]
                ?: parent?.let { intentOfOrder[it] ?: intentOfOrder[it.removeSuffix("-oco")] }
                ?: intentOfOrder[outcome.orderId.substringBeforeLast('-')]
        val event =
            when (outcome) {
                is OrderOutcome.Filled ->
                    SessionEvent(
                        ++seq,
                        "fill",
                        identity,
                        intentId,
                        outcome.orderId,
                        outcome.symbol,
                        outcome.side.name,
                        outcome.price,
                        outcome.quantity,
                        outcome.exitReason?.name,
                        null,
                        outcome.timestamp,
                    )
                is OrderOutcome.Rejected ->
                    SessionEvent(
                        ++seq,
                        "rejected",
                        identity,
                        intentId,
                        outcome.orderId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        outcome.reason,
                        outcome.timestamp,
                    )
                is OrderOutcome.Cancelled ->
                    SessionEvent(
                        ++seq,
                        "cancelled",
                        identity,
                        intentId,
                        outcome.orderId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        outcome.reason,
                        outcome.timestamp,
                    )
            }
        unread += event
        if (outcome !is OrderOutcome.Filled || !outcome.partial) ended += outcome.orderId
        // The intent's own orders settle its fate; the exits the engine placed for it are events only.
        if (intentId != null && intentOfOrder[outcome.orderId] == intentId) settle(intentId, event)
    }

    private fun settle(
        intentId: String,
        event: SessionEvent,
    ) {
        val fate = fates[intentId] ?: return
        val state =
            when (event.kind) {
                "fill" -> "filled"
                else -> event.kind
            }
        fates[intentId] = fate.copy(state = state, reason = event.reason ?: fate.reason, atMs = event.atMs)
        onChange(fates.getValue(intentId))
    }

    @Synchronized
    fun fates(): List<IntentFate> = fates.values.toList()

    /** Events since the previous call, oldest first. */
    @Synchronized
    fun drain(): List<SessionEvent> = unread.toList().also { unread.clear() }

    /** Orders intents placed that have not filled, been cancelled or been rejected. */
    @Synchronized
    fun working(): List<WorkingIntentOrder> =
        intentOfOrder
            .filterKeys { it !in ended }
            .map { (orderId, intentId) -> WorkingIntentOrder(intentId, fates.getValue(intentId).identity, orderId) }
}
