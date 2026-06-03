package com.qkt.notify

/**
 * Describes one configured notify channel as parsed from the config file.
 *
 * Each channel has a [type] ("telegram", "slack", ...) that matches the key in the config block,
 * an [enabled] flag, optional [events] to filter which events this channel receives, and
 * [settings] for channel-specific credentials and tuning (e.g. bot_token, chat_id).
 */
data class ChannelConfig(
    val type: String,
    val enabled: Boolean,
    /** Inbound control surface; parsed now, consumed in a later phase. */
    val commands: Boolean = false,
    val events: Set<NotifyEventKind> = emptySet(),
    /** "" disables the daily summary; "HH:MM" enables it. */
    val dailySummaryUtc: String = "",
    /** Channel-specific keys (e.g. telegram: bot_token, chat_id, queue_capacity). */
    val settings: Map<String, String> = emptyMap(),
)
