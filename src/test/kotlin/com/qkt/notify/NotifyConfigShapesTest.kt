package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifyConfigShapesTest {
    @Test
    fun `a notify block that is not a map is disabled`() {
        assertThat(NotifyConfig.parse("telegram")).isSameAs(NotifyConfig.DISABLED)
        assertThat(NotifyConfig.parse(listOf("telegram"))).isSameAs(NotifyConfig.DISABLED)
    }

    @Test
    fun `a channel whose block is not a map is skipped`() {
        val cfg = NotifyConfig.parse(mapOf("telegram" to "on", "slack" to mapOf("enabled" to true)))

        assertThat(cfg.channels.map { it.type }).containsExactly("slack")
    }

    @Test
    fun `odd values parse leniently into a channel`() {
        val cfg =
            NotifyConfig.parse(
                mapOf(
                    "telegram" to
                        mapOf(
                            "enabled" to "TRUE",
                            "commands" to "no",
                            "events" to listOf("halted", null, 7),
                            "daily_summary_utc" to null,
                            "chat_id" to null,
                            "queue_capacity" to 50,
                        ),
                ),
            )

        val channel = cfg.channels.single()
        assertThat(channel.enabled).isTrue
        assertThat(channel.commands).isFalse
        assertThat(channel.events).containsExactly(NotifyEventKind.HALTED)
        assertThat(channel.dailySummaryUtc).isEmpty()
        assertThat(
            channel.settings,
        ).containsExactlyInAnyOrderEntriesOf(mapOf("chat_id" to "", "queue_capacity" to "50"))
    }

    @Test
    fun `events that are not a list leave the channel with none`() {
        val channel = NotifyConfig.parse(mapOf("telegram" to mapOf("events" to "halted"))).channels.single()

        assertThat(channel.events).isEmpty()
        assertThat(channel.enabled).isFalse
        assertThat(channel.commands).isFalse
    }
}
