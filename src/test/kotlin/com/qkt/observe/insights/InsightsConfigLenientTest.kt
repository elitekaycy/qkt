package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsConfigLenientTest {
    @Test
    fun `a block that is not a map or is enabled only as text stays disabled`() {
        assertThat(InsightsConfig.parse("on")).isSameAs(InsightsConfig.DISABLED)
        assertThat(InsightsConfig.parse(mapOf("enabled" to "true"))).isSameAs(InsightsConfig.DISABLED)
    }

    @Test
    fun `unreadable numbers fall back to the defaults and missing strings read empty`() {
        val cfg =
            InsightsConfig.parse(
                mapOf(
                    "enabled" to true,
                    "flush_interval_ms" to "soon",
                    "batch_size" to "many",
                    "queue_capacity" to 1.5,
                    "state_poll_ms" to "x",
                    "deal_backfill_days" to "y",
                    "journal_enabled" to "true",
                    "events" to "trade",
                ),
            )

        assertThat(cfg.url).isEmpty()
        assertThat(cfg.instanceId).isEmpty()
        assertThat(cfg.token).isEmpty()
        assertThat(cfg.journalDir).isEmpty()
        assertThat(cfg.journalEnabled).isFalse
        assertThat(cfg.flushIntervalMs).isEqualTo(InsightsConfig.DISABLED.flushIntervalMs)
        assertThat(cfg.batchSize).isEqualTo(InsightsConfig.DISABLED.batchSize)
        assertThat(cfg.queueCapacity).isEqualTo(InsightsConfig.DISABLED.queueCapacity)
        assertThat(cfg.statePollMs).isEqualTo(InsightsConfig.DISABLED.statePollMs)
        assertThat(cfg.dealBackfillDays).isEqualTo(InsightsConfig.DISABLED.dealBackfillDays)
        assertThat(cfg.events).isEqualTo(InsightsEventFamily.entries.filter { it.onByDefault }.toSet())
    }

    @Test
    fun `numbers given as numbers are read`() {
        val cfg = InsightsConfig.parse(mapOf("enabled" to true, "batch_size" to 50, "state_poll_ms" to 2_000))

        assertThat(cfg.batchSize).isEqualTo(50)
        assertThat(cfg.statePollMs).isEqualTo(2_000L)
    }
}
