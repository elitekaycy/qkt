package com.qkt.dsl.compile

import com.qkt.common.Side

/**
 * One pending stack registration — the action has emitted a [com.qkt.strategy.Signal.Submit]
 * for the parent leg but no fill has arrived yet. When [com.qkt.events.BrokerEvent.OrderFilled]
 * arrives for [parentClientOrderId], the runtime constructs a [StackEngine] from these tiers.
 */
data class PendingStack(
    val parentClientOrderId: String,
    val symbol: String,
    val side: Side,
    val tiers: List<CompiledStackTier>,
)

/**
 * Per-strategy registry of stack-bearing primary submits awaiting their fill event.
 *
 * The action compiler [register]s an entry the moment its `STACK_AT`-bearing BUY/SELL
 * emits a [com.qkt.strategy.Signal.Submit]; the live runtime [consume]s it when the
 * corresponding [com.qkt.events.BrokerEvent.OrderFilled] arrives, then hands the tiers
 * to the [StackOrchestrator].
 *
 * Single-writer (the action compiler thread) and single-reader (the bus dispatcher),
 * but both walk in the same JVM and the engine is single-threaded per [com.qkt.app.LiveSession],
 * so the underlying map needs no synchronization.
 */
class PendingStacks {
    private val byClientOrderId: MutableMap<String, PendingStack> = mutableMapOf()

    fun register(entry: PendingStack) {
        check(entry.parentClientOrderId !in byClientOrderId) {
            "PendingStacks: duplicate registration for clientOrderId=${entry.parentClientOrderId}"
        }
        byClientOrderId[entry.parentClientOrderId] = entry
    }

    fun consume(clientOrderId: String): PendingStack? = byClientOrderId.remove(clientOrderId)

    fun size(): Int = byClientOrderId.size

    fun contains(clientOrderId: String): Boolean = clientOrderId in byClientOrderId
}
