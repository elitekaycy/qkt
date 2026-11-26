package com.qkt.dsl.sets

import com.qkt.events.Event
import com.qkt.events.SetMembershipChanged
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Where a [DynamicSetHost]'s membership changes go: the pipeline's bus ([publish], once bound) and an [observer]. A
 * change found off the tick thread (a live candidate that could not be subscribed) is held ([later]) and recorded at
 * the next tick ([drain]), so every change reaches the bus from the thread that runs the strategy.
 */
internal class MembershipJournal(
    private val observer: (SetMembershipChanged) -> Unit,
) {
    private val pending = ConcurrentLinkedQueue<SetMembershipChanged>()

    /** The pipeline's bus; nothing until the strategy is bound. */
    var publish: (Event) -> Unit = {}

    fun record(event: SetMembershipChanged) {
        publish(event)
        observer(event)
    }

    /** Holds [event] for the next [drain]; safe from any thread. */
    fun later(event: SetMembershipChanged) {
        pending += event
    }

    /** Records every held change under [strategyId], oldest first. */
    fun drain(strategyId: String) {
        while (true) record(pending.poll()?.copy(strategyId = strategyId) ?: return)
    }
}
