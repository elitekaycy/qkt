package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifierFactoryTest {
    private fun telegram(
        enabled: Boolean,
        botToken: String = "bot-token",
        chatId: String = "chat-id",
    ) = TelegramConfig(
        enabled = enabled,
        botToken = botToken,
        chatId = chatId,
        dailySummaryUtc = "",
        queueCapacity = 100,
        events = emptySet(),
    )

    @Test
    fun `fromConfig returns NoopNotifier when telegram is disabled`() {
        assertThat(NotifierFactory.fromConfig(telegram(enabled = false))).isSameAs(NoopNotifier)
    }

    @Test
    fun `fromConfig returns NoopNotifier when bot token is missing`() {
        assertThat(NotifierFactory.fromConfig(telegram(enabled = true, botToken = ""))).isSameAs(NoopNotifier)
    }

    @Test
    fun `fromConfig returns NoopNotifier when chat id is missing`() {
        assertThat(NotifierFactory.fromConfig(telegram(enabled = true, chatId = ""))).isSameAs(NoopNotifier)
    }

    @Test
    fun `fromConfig builds a TelegramNotifier when enabled and fully configured`() {
        val notifier = NotifierFactory.fromConfig(telegram(enabled = true))
        try {
            assertThat(notifier).isInstanceOf(TelegramNotifier::class.java)
        } finally {
            notifier.close()
        }
    }
}
