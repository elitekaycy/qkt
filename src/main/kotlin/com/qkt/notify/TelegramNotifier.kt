package com.qkt.notify

import org.slf4j.LoggerFactory

/**
 * Production [Notifier]. Formats each event with [MessageTemplate] and enqueues to a
 * [NotificationWorker]. Never blocks or throws — the worker handles I/O on its own thread.
 *
 * Construction does not subscribe to the bus; [com.qkt.app.LiveSession] wires up the bus
 * subscriptions. This class is just the sink.
 */
class TelegramNotifier(
    private val worker: NotificationWorker,
    override val metrics: AtomicNotifierMetrics,
) : Notifier {
    private val log = LoggerFactory.getLogger(TelegramNotifier::class.java)

    override fun notify(event: NotificationEvent) {
        val text =
            try {
                MessageTemplate.format(event)
            } catch (t: Throwable) {
                log.warn("[notify] template failed for {}", event::class.simpleName, t)
                metrics.recordDropped()
                return
            }
        worker.enqueue(text)
    }

    override fun close() {
        worker.close()
    }
}
