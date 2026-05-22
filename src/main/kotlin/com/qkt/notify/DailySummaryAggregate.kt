package com.qkt.notify

import java.time.Instant
import java.time.ZoneOffset

/**
 * Build a single [NotificationEvent.DailySummary] from the per-strategy rows of every
 * live session a daemon hosts. A multi-strategy daemon then sends one summary message
 * at the UTC tick instead of one per session.
 */
fun aggregateDailySummary(
    rowsPerSession: List<List<StrategySummary>>,
    nowMs: Long,
): NotificationEvent.DailySummary =
    NotificationEvent.DailySummary(
        asOfUtc =
            Instant
                .ofEpochMilli(nowMs)
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .toString(),
        strategies = rowsPerSession.flatten(),
        timestamp = nowMs,
    )
