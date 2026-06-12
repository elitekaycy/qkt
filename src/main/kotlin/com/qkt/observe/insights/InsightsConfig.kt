package com.qkt.observe.insights

/**
 * Event families a qkt instance can stream to the insights collector. Each family
 * maps to one or more [com.qkt.events.Event] types; the allow-list in
 * [InsightsConfig.events] decides which families get a bus subscription at wire time.
 */
enum class InsightsEventFamily(
    val configName: String,
) {
    TRADE("trade"),
    ORDER("order"),
    SIGNAL("signal"),
    RISK("risk"),
    POSITION("position"),
    SNAPSHOT("snapshot"),
    LOG("log"),
    STATE("state"),
    DEAL("deal"),
    ;

    companion object {
        fun fromConfigName(name: String): InsightsEventFamily? = entries.firstOrNull { it.configName == name }
    }
}

/**
 * Opt-in egress to a qkt-insights collector, parsed from the `insights:` block of
 * `qkt.config.yaml`. Disabled (the default) wires nothing: no thread, no queue.
 *
 * ```yaml
 * insights:
 *   enabled: true
 *   url: "http://insights-host:8420/ingest"
 *   instance_id: "qkt-prod"
 *   token: "${INGEST_TOKEN}"
 *   events: [trade, order, signal, risk, position, state, deal]
 *   flush_interval_ms: 250
 *   batch_size: 200
 *   queue_capacity: 10000
 *   snapshot_interval_ms: 5000
 *   state_poll_ms: 10000
 *   deal_backfill_days: 30
 * ```
 */
data class InsightsConfig(
    val enabled: Boolean,
    val url: String,
    val instanceId: String,
    val token: String,
    val events: Set<InsightsEventFamily>,
    val flushIntervalMs: Long,
    val batchSize: Int,
    val queueCapacity: Int,
    val snapshotIntervalMs: Long,
    /** Cadence of the broker state poller (account/positions/deals), milliseconds. */
    val statePollMs: Long,
    /** How far back the poller backfills broker deal history on startup, in days. */
    val dealBackfillDays: Long,
) {
    companion object {
        val DISABLED: InsightsConfig =
            InsightsConfig(
                enabled = false,
                url = "",
                instanceId = "",
                token = "",
                events = emptySet(),
                flushIntervalMs = 250L,
                batchSize = 200,
                queueCapacity = 10_000,
                snapshotIntervalMs = 5_000L,
                statePollMs = 10_000L,
                dealBackfillDays = 30L,
            )

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): InsightsConfig {
            val map = raw as? Map<String, Any?> ?: return DISABLED
            val enabled = map["enabled"] == true
            if (!enabled) return DISABLED
            val families =
                (map["events"] as? List<Any?>)
                    .orEmpty()
                    .mapNotNull { InsightsEventFamily.fromConfigName(it.toString()) }
                    .toSet()
                    .ifEmpty { InsightsEventFamily.entries.toSet() }
            return InsightsConfig(
                enabled = true,
                url = map["url"]?.toString().orEmpty(),
                instanceId = map["instance_id"]?.toString().orEmpty(),
                token = map["token"]?.toString().orEmpty(),
                events = families,
                flushIntervalMs = map["flush_interval_ms"]?.toString()?.toLongOrNull() ?: DISABLED.flushIntervalMs,
                batchSize = map["batch_size"]?.toString()?.toIntOrNull() ?: DISABLED.batchSize,
                queueCapacity = map["queue_capacity"]?.toString()?.toIntOrNull() ?: DISABLED.queueCapacity,
                snapshotIntervalMs =
                    map["snapshot_interval_ms"]?.toString()?.toLongOrNull() ?: DISABLED.snapshotIntervalMs,
                statePollMs = map["state_poll_ms"]?.toString()?.toLongOrNull() ?: DISABLED.statePollMs,
                dealBackfillDays =
                    map["deal_backfill_days"]?.toString()?.toLongOrNull() ?: DISABLED.dealBackfillDays,
            )
        }
    }
}
