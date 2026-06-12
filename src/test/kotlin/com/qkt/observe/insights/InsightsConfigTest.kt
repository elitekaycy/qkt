package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsConfigTest {
    @Test
    fun `absent block parses to disabled`() {
        assertThat(InsightsConfig.parse(null)).isEqualTo(InsightsConfig.DISABLED)
        assertThat(InsightsConfig.parse(mapOf<String, Any?>("enabled" to false))).isEqualTo(InsightsConfig.DISABLED)
    }

    @Test
    fun `enabled block parses url, token, families and tuning knobs`() {
        val cfg =
            InsightsConfig.parse(
                mapOf(
                    "enabled" to true,
                    "url" to "http://host:8420/ingest",
                    "instance_id" to "qkt-prod",
                    "token" to "secret",
                    "events" to listOf("trade", "order", "snapshot"),
                    "flush_interval_ms" to 100,
                    "batch_size" to 50,
                    "queue_capacity" to 1000,
                    "snapshot_interval_ms" to 2000,
                    "state_poll_ms" to 4000,
                    "deal_backfill_days" to 7,
                ),
            )
        assertThat(cfg.enabled).isTrue()
        assertThat(cfg.url).isEqualTo("http://host:8420/ingest")
        assertThat(cfg.instanceId).isEqualTo("qkt-prod")
        assertThat(cfg.token).isEqualTo("secret")
        assertThat(cfg.events)
            .containsExactlyInAnyOrder(
                InsightsEventFamily.TRADE,
                InsightsEventFamily.ORDER,
                InsightsEventFamily.SNAPSHOT,
            )
        assertThat(cfg.flushIntervalMs).isEqualTo(100L)
        assertThat(cfg.batchSize).isEqualTo(50)
        assertThat(cfg.queueCapacity).isEqualTo(1000)
        assertThat(cfg.snapshotIntervalMs).isEqualTo(2000L)
        assertThat(cfg.statePollMs).isEqualTo(4000L)
        assertThat(cfg.dealBackfillDays).isEqualTo(7L)
    }

    @Test
    fun `state poll and deal backfill default when absent`() {
        val cfg = InsightsConfig.parse(mapOf("enabled" to true, "url" to "http://h/ingest"))
        assertThat(cfg.statePollMs).isEqualTo(10_000L)
        assertThat(cfg.dealBackfillDays).isEqualTo(30L)
    }

    @Test
    fun `state and deal family names parse`() {
        val cfg =
            InsightsConfig.parse(
                mapOf("enabled" to true, "url" to "http://h/ingest", "events" to listOf("state", "deal")),
            )
        assertThat(cfg.events).containsExactlyInAnyOrder(InsightsEventFamily.STATE, InsightsEventFamily.DEAL)
    }

    @Test
    fun `missing events list enables every family`() {
        val cfg = InsightsConfig.parse(mapOf("enabled" to true, "url" to "http://h/ingest"))
        assertThat(cfg.events).containsExactlyInAnyOrderElementsOf(InsightsEventFamily.entries)
    }

    @Test
    fun `unknown family names are ignored`() {
        val cfg =
            InsightsConfig.parse(
                mapOf("enabled" to true, "url" to "http://h/ingest", "events" to listOf("trade", "bogus")),
            )
        assertThat(cfg.events).containsExactly(InsightsEventFamily.TRADE)
    }
}
