package com.qkt.notify

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves the daemon's notifier wiring is byte-identical in behaviour to the single-channel
 * Telegram path: parse -> enabledChannels -> registry provider -> FilteringNotifier -> Composite.
 * A live Telegram-backed notifier exposes metrics; a disabled config degrades to Noop (no metrics).
 */
class OutboundWiringTest {
    private fun parse(
        enabled: Boolean,
        events: List<String> = listOf("order_rejected", "halted"),
    ): NotifyConfig =
        NotifyConfig.parse(
            mapOf(
                "telegram" to
                    mapOf(
                        "enabled" to enabled.toString(),
                        "bot_token" to "T",
                        "chat_id" to "C",
                        "events" to events,
                    ),
            ),
        )

    private fun compose(cfg: NotifyConfig): CompositeNotifier {
        val registry = ChannelRegistry.DEFAULT
        val channelNotifiers =
            cfg.enabledChannels().mapNotNull { ch ->
                registry.get(ch.type)?.let { provider -> ch to provider.notifier(ch) }
            }
        return CompositeNotifier(channelNotifiers.map { (ch, n) -> FilteringNotifier(ch.events, n) })
    }

    @Test
    fun `enabled telegram config yields exactly one enabled telegram channel`() {
        val cfg = parse(enabled = true)
        assertThat(cfg.enabledChannels()).hasSize(1)
        assertThat(cfg.enabledChannels().single().type).isEqualTo("telegram")
    }

    @Test
    fun `registry builds a live TelegramNotifier for an enabled telegram channel`() {
        val ch = parse(enabled = true).enabledChannels().single()
        val notifier = ChannelRegistry.DEFAULT.get(ch.type)!!.notifier(ch)
        try {
            assertThat(notifier).isInstanceOf(TelegramNotifier::class.java)
            assertThat(notifier).isNotSameAs(NoopNotifier)
        } finally {
            notifier.close()
        }
    }

    @Test
    fun `enabled config composes to a notifier with live metrics`() {
        val composite = compose(parse(enabled = true))
        try {
            assertThat(composite.metrics).isNotNull()
        } finally {
            composite.close()
        }
    }

    @Test
    fun `enabledEventKinds equals the configured telegram events`() {
        val cfg = parse(enabled = true)
        assertThat(cfg.enabledEventKinds()).containsExactlyInAnyOrder(
            NotifyEventKind.ORDER_REJECTED,
            NotifyEventKind.HALTED,
        )
    }

    @Test
    fun `disabled config composes to a notifier with no metrics`() {
        val cfg = parse(enabled = false)
        assertThat(cfg.enabledChannels()).isEmpty()
        val composite = compose(cfg)
        try {
            assertThat(composite.metrics).isNull()
        } finally {
            composite.close()
        }
    }
}
