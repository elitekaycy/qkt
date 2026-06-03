package com.qkt.notify

/**
 * Extension point for a single channel type in the notify subsystem.
 *
 * A provider knows how to build a live [Notifier] from a resolved [ChannelConfig]. The registry
 * holds one provider per channel type and calls [notifier] at startup. For example, the telegram
 * provider reads notify.telegram config and builds a TelegramNotifier (or NoopNotifier when
 * the channel is disabled or credentials are absent).
 */
interface ChannelProvider {
    /** Discriminator that matches the config block name, e.g. "telegram". */
    val type: String

    /**
     * Build a [Notifier] for this channel from [config].
     *
     * Returns [NoopNotifier] when the channel is disabled or unconfigured; otherwise returns
     * a live notifier that delivers events over the channel's transport.
     */
    fun notifier(config: ChannelConfig): Notifier
}
