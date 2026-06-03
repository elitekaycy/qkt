package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramChannelProviderTest {
    private val provider = TelegramChannelProvider()

    private fun config(
        enabled: Boolean,
        botToken: String = "bot-token",
        chatId: String = "chat-id",
        queueCapacity: String? = null,
    ): ChannelConfig {
        val settings =
            buildMap<String, String> {
                put("bot_token", botToken)
                put("chat_id", chatId)
                if (queueCapacity != null) put("queue_capacity", queueCapacity)
            }
        return ChannelConfig(type = "telegram", enabled = enabled, settings = settings)
    }

    @Test
    fun `notifier returns TelegramNotifier when enabled and fully configured`() {
        val notifier = provider.notifier(config(enabled = true))
        try {
            assertThat(notifier).isInstanceOf(TelegramNotifier::class.java)
            assertThat(notifier).isNotSameAs(NoopNotifier)
        } finally {
            notifier.close()
        }
    }

    @Test
    fun `notifier returns NoopNotifier when disabled`() {
        assertThat(provider.notifier(config(enabled = false))).isSameAs(NoopNotifier)
    }

    @Test
    fun `notifier returns NoopNotifier when bot_token is blank`() {
        assertThat(provider.notifier(config(enabled = true, botToken = ""))).isSameAs(NoopNotifier)
    }

    @Test
    fun `notifier returns NoopNotifier when chat_id is blank`() {
        assertThat(provider.notifier(config(enabled = true, chatId = ""))).isSameAs(NoopNotifier)
    }

    @Test
    fun `notifier builds TelegramNotifier when queue_capacity setting is absent`() {
        val notifier = provider.notifier(config(enabled = true))
        try {
            assertThat(notifier).isInstanceOf(TelegramNotifier::class.java)
        } finally {
            notifier.close()
        }
    }
}
