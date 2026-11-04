package com.qkt.app

import java.util.concurrent.BlockingQueue

/**
 * Processes the ticks a heartbeat stamped [nowMs] must not overtake (#1058) with [process]: every tick queued when
 * the heartbeat is handled, then any later arrival stamped at or before [nowMs], at most one more queue's worth. A
 * tick stamped after the heartbeat waits for the loop's next turn. Draining until the queue is empty never ends while
 * a feed outpaces the engine, and every control message behind the heartbeat (a fill, a P&L read) waits as long
 * (#1609).
 * Returns how many ticks were processed.
 */
internal fun drainTicksAheadOf(
    queue: BlockingQueue<Inbound.FeedTick>,
    nowMs: Long,
    process: (Inbound.FeedTick) -> Unit,
): Int {
    val queued = queue.size
    val capacity = queued + queue.remainingCapacity()
    val most = queued + capacity
    var processed = 0
    while (processed < most) {
        val head = queue.peek() ?: break
        if (processed >= queued && head.tick.timestamp > nowMs) break
        process(queue.poll() ?: break)
        processed++
    }
    return processed
}
