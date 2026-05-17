package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifyConfigTest {
    @Test
    fun `parse returns disabled config when input is null`() {
        val c = NotifyConfig.parse(null)
        assertThat(c.telegram.enabled).isFalse()
        assertThat(c.telegram.botToken).isEmpty()
        assertThat(c.telegram.chatId).isEmpty()
    }

    @Test
    fun `parse honors enabled bot_token chat_id`() {
        val c =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to
                        mapOf(
                            "enabled" to "true",
                            "bot_token" to "T",
                            "chat_id" to "C",
                            "daily_summary_utc" to "00:00",
                            "queue_capacity" to "100",
                            "events" to listOf("order_rejected", "halted"),
                        ),
                ),
            )
        assertThat(c.telegram.enabled).isTrue()
        assertThat(c.telegram.botToken).isEqualTo("T")
        assertThat(c.telegram.chatId).isEqualTo("C")
        assertThat(c.telegram.dailySummaryUtc).isEqualTo("00:00")
        assertThat(c.telegram.queueCapacity).isEqualTo(100)
        assertThat(c.telegram.events).containsExactlyInAnyOrder(
            NotifyEventKind.ORDER_REJECTED,
            NotifyEventKind.HALTED,
        )
    }

    @Test
    fun `parse with unknown event name keeps the rest and logs WARN`() {
        val c =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to
                        mapOf(
                            "enabled" to "true",
                            "bot_token" to "T",
                            "chat_id" to "C",
                            "events" to listOf("order_rejected", "future_event", "halted"),
                        ),
                ),
            )
        assertThat(c.telegram.events).containsExactlyInAnyOrder(
            NotifyEventKind.ORDER_REJECTED,
            NotifyEventKind.HALTED,
        )
    }

    @Test
    fun `parse defaults queue_capacity to 100 and disables daily_summary_utc when missing`() {
        val c =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to mapOf("enabled" to "false"),
                ),
            )
        assertThat(c.telegram.queueCapacity).isEqualTo(100)
        // Empty dailySummaryUtc disables the scheduler — operators must explicitly opt in by
        // setting daily_summary_utc to a "HH:MM" value in qkt.config.yaml.
        assertThat(c.telegram.dailySummaryUtc).isEmpty()
    }
}
