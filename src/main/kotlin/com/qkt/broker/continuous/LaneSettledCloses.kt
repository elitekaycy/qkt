package com.qkt.broker.continuous

import com.qkt.broker.Broker
import com.qkt.broker.SettledClose
import com.qkt.broker.settledClose
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.FillAccountedEvent

/**
 * The settlement closes a live lane's strategies were booked, kept with the lane's state: a venue deal list
 * states such a close only from what qkt booked, so a restart must not lose it (#1570). Each is kept for
 * [RETAIN_MS], as far back as a deal poll reads after a restart by default; it may be reported again in that
 * time, under the same ticket, which a deal's reader already takes once. A backtest's lane ([live] false) keeps none.
 */
internal class LaneSettledCloses(
    bus: EventBus,
    private val clock: Clock,
    stream: String,
    private val venue: Broker,
    private val save: () -> Unit,
    live: Boolean,
) {
    private val closes = ArrayList<SettledClose>()

    /** The closes kept now, oldest first. */
    val all: List<SettledClose> get() = closes.toList()

    init {
        if (live) {
            bus.subscribe<FillAccountedEvent> { e ->
                val close = e.takeIf { it.symbol == stream }?.settledClose() ?: return@subscribe
                closes += close
                forgetOld()
                save()
            }
        }
    }

    /** Puts back what a restart [saved], and hands it to the venue that reports the deals. */
    fun restore(saved: List<SettledClose>) {
        closes += saved
        forgetOld()
        venue.restoreSettledCloses(all)
    }

    private fun forgetOld() {
        val oldest = clock.now() - RETAIN_MS
        closes.removeIf { it.atMs < oldest }
    }

    private companion object {
        const val RETAIN_MS = 30 * 86_400_000L
    }
}
