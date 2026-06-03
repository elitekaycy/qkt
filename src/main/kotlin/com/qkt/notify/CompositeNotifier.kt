package com.qkt.notify

/**
 * Fans one [NotificationEvent] out to every notifier in [notifiers].
 *
 * e.g. two enabled channels -> notify(event) reaches both; close() drains both even if one
 * throws.
 */
class CompositeNotifier(
    private val notifiers: List<Notifier>,
) : Notifier {
    override fun notify(event: NotificationEvent) {
        notifiers.forEach { it.notify(event) }
    }

    /**
     * The first channel that exposes metrics. The control plane reads a single metrics surface;
     * with one channel this is that channel's metrics, unchanged from today.
     */
    override val metrics: NotifierMetrics?
        get() = notifiers.firstNotNullOfOrNull { it.metrics }

    override fun close() {
        notifiers.forEach { runCatching { it.close() } }
    }
}
