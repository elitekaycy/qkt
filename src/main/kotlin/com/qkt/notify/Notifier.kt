package com.qkt.notify

/**
 * Sink for [NotificationEvent]s. Implementations must:
 *  - return immediately from [notify] (O(1), non-blocking, never throws);
 *  - perform any I/O on a separate thread.
 *
 * This contract is what keeps trading paths from blocking on Telegram I/O.
 */
interface Notifier : AutoCloseable {
    fun notify(event: NotificationEvent)
}
