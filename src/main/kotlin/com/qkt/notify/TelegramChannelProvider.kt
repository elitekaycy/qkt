package com.qkt.notify

/** [ChannelProvider] for Telegram. Builds a [TelegramNotifier] from notify.telegram config. */
class TelegramChannelProvider : ChannelProvider {
    override val type = "telegram"

    override fun notifier(config: ChannelConfig): Notifier {
        val botToken = config.settings["bot_token"].orEmpty()
        val chatId = config.settings["chat_id"].orEmpty()
        val queueCapacity = config.settings["queue_capacity"]?.toIntOrNull() ?: 100
        if (!config.enabled || botToken.isEmpty() || chatId.isEmpty()) {
            return NoopNotifier
        }
        val metrics = AtomicNotifierMetrics()
        val client = TelegramClient(TELEGRAM_API, botToken, chatId)
        val worker =
            NotificationWorker(
                send = client::send,
                metrics = metrics,
                queueCapacity = queueCapacity,
            )
        return TelegramNotifier(worker, metrics)
    }

    private companion object {
        const val TELEGRAM_API = "https://api.telegram.org"
    }
}
