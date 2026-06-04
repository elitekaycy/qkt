package com.qkt.cli.observe

import java.time.Instant
import kotlinx.serialization.json.Json

/** Pure: turns the raw control-plane responses + a window/schedule into the three gate results. */
object ObserveRunner {
    private val json = Json { ignoreUnknownKeys = true }

    fun run(
        from: Instant,
        to: Instant,
        schedule: PlacementSchedule,
        logsText: String,
        statusJson: String,
    ): List<GateResult> {
        val logs = LogScan.parse(logsText)
        val status = json.decodeFromString(StatusSnapshot.serializer(), statusJson)
        return listOf(
            GateEvaluator.placement(logs, schedule, from, to),
            GateEvaluator.errors(logs),
            GateEvaluator.pnl(logs, status.realized, status.unrealized),
        )
    }
}
