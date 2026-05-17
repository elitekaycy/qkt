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

data class TelegramConfig(
    val enabled: Boolean,
    val botToken: String,
    val chatId: String,
    val dailySummaryUtc: String, // "" disables
    val queueCapacity: Int,
    val events: Set<NotifyEventKind>,
)

data class NotifyConfig(
    val telegram: TelegramConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(NotifyConfig::class.java)

        val DISABLED: NotifyConfig =
            NotifyConfig(
                telegram =
                    TelegramConfig(
                        enabled = false,
                        botToken = "",
                        chatId = "",
                        dailySummaryUtc = "00:00",
                        queueCapacity = 100,
                        events = emptySet(),
                    ),
            )

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): NotifyConfig {
            if (raw == null) return DISABLED
            val map = raw as? Map<String, Any?> ?: return DISABLED
            val tg = map["telegram"] as? Map<String, Any?> ?: return DISABLED
            val events =
                (tg["events"] as? List<Any?>)
                    ?.mapNotNull { it?.toString() }
                    ?.mapNotNull { name ->
                        NotifyEventKind.BY_NAME[name].also {
                            if (it == null) log.warn("[notify] unknown event in config: {}", name)
                        }
                    }?.toSet()
                    ?: emptySet()
            return NotifyConfig(
                telegram =
                    TelegramConfig(
                        enabled = (tg["enabled"]?.toString()?.equals("true", ignoreCase = true)) ?: false,
                        botToken = tg["bot_token"]?.toString().orEmpty(),
                        chatId = tg["chat_id"]?.toString().orEmpty(),
                        dailySummaryUtc = tg["daily_summary_utc"]?.toString() ?: "00:00",
                        queueCapacity = tg["queue_capacity"]?.toString()?.toIntOrNull() ?: 100,
                        events = events,
                    ),
            )
        }
    }
}
