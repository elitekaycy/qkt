package com.qkt.notify

/**
 * Builds the [Notifier] a daemon uses for Telegram alerts.
 *
 * Returns [NoopNotifier] unless Telegram is enabled and both credentials are present;
 * otherwise assembles a [TelegramNotifier] over a live [TelegramClient] and its worker.
 */
object NotifierFactory {
    private const val TELEGRAM_API = "https://api.telegram.org"

    /** Construct a [Notifier] from the resolved Telegram configuration. */
    fun fromConfig(telegram: TelegramConfig): Notifier {
        if (!telegram.enabled || telegram.botToken.isEmpty() || telegram.chatId.isEmpty()) {
            return NoopNotifier
        }
        val metrics = AtomicNotifierMetrics()
        val client = TelegramClient(TELEGRAM_API, telegram.botToken, telegram.chatId)
        val worker =
            NotificationWorker(
                send = client::send,
                metrics = metrics,
                queueCapacity = telegram.queueCapacity,
            )
        return TelegramNotifier(worker, metrics)
    }
}
