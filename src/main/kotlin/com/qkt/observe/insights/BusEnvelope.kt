package com.qkt.observe.insights

/**
 * Wraps one bus event's payload in an [InsightsEnvelope]. The id combines type, strategy,
 * timestamp and bus sequence so a re-sent batch dedupes at the collector without colliding
 * with another strategy session's bus sequence.
 */
internal fun busEnvelope(
    seq: Long,
    ts: Long,
    strategyId: String?,
    type: String,
    payload: Map<String, Any?>,
): InsightsEnvelope =
    InsightsEnvelope(
        id = "event-$type-${strategyId.orEmpty()}-$ts-$seq",
        seq = seq,
        ts = ts,
        strategyId = strategyId?.takeIf { it.isNotBlank() },
        type = type,
        payload = payload,
    )
