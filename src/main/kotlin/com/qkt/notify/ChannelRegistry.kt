package com.qkt.notify

/**
 * Maps channel [type] strings to their [ChannelProvider] implementations.
 *
 * This is the single place where known channel types are registered. At startup the application
 * calls [get] for each configured channel block to retrieve the right provider, then asks the
 * provider to build a [Notifier] from the resolved [ChannelConfig].
 *
 * Use [DEFAULT] to get the registry with every built-in provider pre-registered.
 */
class ChannelRegistry(
    providers: List<ChannelProvider>,
) {
    private val byType: Map<String, ChannelProvider> = providers.associateBy { it.type }

    /** Returns the provider for [type], or null if no provider is registered for that type. */
    fun get(type: String): ChannelProvider? = byType[type]

    /** All channel type strings known to this registry. */
    val types: Set<String> get() = byType.keys

    companion object {
        /** The registry with every built-in channel provider pre-registered. */
        val DEFAULT: ChannelRegistry = ChannelRegistry(listOf(TelegramChannelProvider()))
    }
}
