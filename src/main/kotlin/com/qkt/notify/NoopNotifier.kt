package com.qkt.notify

/** [Notifier] that discards every event. Default when alerts are disabled or unconfigured. */
object NoopNotifier : Notifier {
    override fun notify(event: NotificationEvent) {}

    override fun close() {}
}
