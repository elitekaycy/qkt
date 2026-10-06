package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.events.Event
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The private buses of a session's continuous-stream lanes, bound to its engine loop as the session bus
 * is: a publish from any other thread (a gateway attachment's fill) is queued on [mailbox] and published
 * on the engine thread, in order with the session bus's own queued events, so a lane only ever runs on
 * the engine thread. Bind each bus before its venue is built ([bind]), then [attach] the loop thread.
 */
internal class LaneBuses(
    private val mailbox: EngineMailbox,
) {
    private val buses = CopyOnWriteArrayList<EventBus>()

    /** Queues [bus]'s publishes for the engine loop until it is [attach]ed. */
    fun bind(bus: EventBus) {
        bus.bindSink(sinkFor(bus))
        buses += bus
    }

    /** Routes every bound bus's off-thread publishes onto the engine loop running on [thread]. */
    fun attach(thread: Thread) = buses.forEach { it.bindEngineLoop(thread, sinkFor(it)) }

    private fun sinkFor(bus: EventBus): (Event) -> Unit = { event -> mailbox.postOnEngine { bus.publish(event) } }
}
