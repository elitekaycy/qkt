package com.qkt.notify

import org.slf4j.LoggerFactory

enum class NotifyEventKind(
    val configName: String,
) {
    ORDER_REJECTED("order_rejected"),
    HALTED("halted"),
    RESUMED("resumed"),
    POSITION_RECONCILED("position_reconciled"),
    STRATEGY_STARTED("strategy_started"),
    STRATEGY_STOPPED("strategy_stopped"),
    STRATEGY_ERROR("strategy_error"),
    DAEMON_STARTED("daemon_started"),
    ;

    companion object {
        val BY_NAME: Map<String, NotifyEventKind> = values().associateBy { it.configName }
    }
}

data class NotifyConfig(
    val channels: List<ChannelConfig>,
) {
    fun enabledChannels(): List<ChannelConfig> = channels.filter { it.enabled }

    fun enabledEventKinds(): Set<NotifyEventKind> = enabledChannels().flatMap { it.events }.toSet()

    companion object {
        private val log = LoggerFactory.getLogger(NotifyConfig::class.java)
        private val COMMON_KEYS = setOf("enabled", "commands", "events", "daily_summary_utc")

        val DISABLED: NotifyConfig = NotifyConfig(emptyList())

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): NotifyConfig {
            if (raw == null) return DISABLED
            val map = raw as? Map<String, Any?> ?: return DISABLED
            val channels =
                map.entries.mapNotNull { (type, block) ->
                    val sub = block as? Map<String, Any?> ?: return@mapNotNull null
                    parseChannel(type, sub)
                }
            return NotifyConfig(channels)
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseChannel(
            type: String,
            sub: Map<String, Any?>,
        ): ChannelConfig {
            val events =
                (sub["events"] as? List<Any?>)
                    ?.mapNotNull { it?.toString() }
                    ?.mapNotNull { name ->
                        NotifyEventKind.BY_NAME[name].also {
                            if (it == null) log.warn("[notify] unknown event in config: {}", name)
                        }
                    }?.toSet()
                    ?: emptySet()
            val settings =
                sub
                    .filterKeys { it !in COMMON_KEYS }
                    .mapValues { (_, v) -> v?.toString() ?: "" }
            return ChannelConfig(
                type = type,
                enabled = sub["enabled"]?.toString()?.equals("true", ignoreCase = true) ?: false,
                commands = sub["commands"]?.toString()?.equals("true", ignoreCase = true) ?: false,
                events = events,
                dailySummaryUtc = sub["daily_summary_utc"]?.toString() ?: "",
                settings = settings,
            )
        }
    }
}
