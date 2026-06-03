package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifyConfigTest {
    @Test
    fun `parse returns no channels when input is null`() {
        val c = NotifyConfig.parse(null)
        assertThat(c.channels).isEmpty()
        assertThat(c.enabledChannels()).isEmpty()
    }

    @Test
    fun `parse splits common fields and channel settings for a telegram block`() {
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
        assertThat(c.channels).hasSize(1)
        val ch = c.channels.single()
        assertThat(ch.type).isEqualTo("telegram")
        assertThat(ch.enabled).isTrue()
        assertThat(ch.commands).isFalse()
        assertThat(ch.events).containsExactlyInAnyOrder(
            NotifyEventKind.ORDER_REJECTED,
            NotifyEventKind.HALTED,
        )
        assertThat(ch.dailySummaryUtc).isEqualTo("00:00")
        assertThat(ch.settings)
            .containsEntry("bot_token", "T")
            .containsEntry("chat_id", "C")
            .containsEntry("queue_capacity", "100")
            .doesNotContainKeys("enabled", "events", "daily_summary_utc", "commands")
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
        assertThat(c.channels.single().events).containsExactlyInAnyOrder(
            NotifyEventKind.ORDER_REJECTED,
            NotifyEventKind.HALTED,
        )
    }

    @Test
    fun `parse keeps a disabled channel but enabledChannels excludes it`() {
        val c =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to mapOf("enabled" to "false"),
                ),
            )
        assertThat(c.channels).hasSize(1)
        assertThat(c.enabledChannels()).isEmpty()
    }

    @Test
    fun `parse defaults missing daily_summary_utc to empty and omits absent settings keys`() {
        val c =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to mapOf("enabled" to "true", "bot_token" to "T", "chat_id" to "C"),
                ),
            )
        val ch = c.channels.single()
        // Empty dailySummaryUtc disables the scheduler — operators must explicitly opt in by
        // setting daily_summary_utc to a "HH:MM" value in qkt.config.yaml.
        assertThat(ch.dailySummaryUtc).isEmpty()
        assertThat(ch.settings).doesNotContainKey("queue_capacity")
    }

    @Test
    fun `enabledEventKinds unions enabled channels and excludes disabled ones`() {
        val c =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to
                        mapOf(
                            "enabled" to "true",
                            "events" to listOf("halted"),
                        ),
                    "slack" to
                        mapOf(
                            "enabled" to "true",
                            "events" to listOf("resumed"),
                        ),
                    "discord" to
                        mapOf(
                            "enabled" to "false",
                            "events" to listOf("order_rejected"),
                        ),
                ),
            )
        assertThat(c.enabledEventKinds()).containsExactlyInAnyOrder(
            NotifyEventKind.HALTED,
            NotifyEventKind.RESUMED,
        )
    }
}
