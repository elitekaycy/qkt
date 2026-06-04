package com.qkt.cli.observe

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/** Outcome of one go-live gate. */
enum class GateStatus { PASS, FAIL, REVIEW }

/** One gate's verdict plus human-readable detail lines. */
data class GateResult(
    val name: String,
    val status: GateStatus,
    val detail: List<String>,
)

/** Expected placement windows: the given UTC [hours] at [minute] (e.g. hedge-straddle 6,7,12,13,14,15 @55). */
data class PlacementSchedule(
    val hours: Set<Int>,
    val minute: Int,
)

/** Pure evaluators for the three #33 go-live gates over already-parsed [LogLine]s + a `/status` snapshot. */
object GateEvaluator {
    // Benign WARNs that recur in normal operation and must not fail the error gate.
    private val BENIGN_WARN =
        listOf(
            "seeding orphan ticket", // MT5 truncated-prefix attribution note
            "already exists but was not created by Docker Compose",
        )

    /**
     * Gate 1 — for each expected window (scheduled hour at [PlacementSchedule.minute]) inside
     * `[from, to)`, require at least one `submit` log line in the minute..minute+2 band.
     */
    fun placement(
        logs: List<LogLine>,
        schedule: PlacementSchedule,
        from: Instant,
        to: Instant,
    ): GateResult {
        val submits = logs.filter { it.isSubmit }
        val detail = mutableListOf<String>()
        var missed = 0
        var window =
            from
                .atZone(ZoneOffset.UTC)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        while (window.toInstant().isBefore(to)) {
            if (window.hour in schedule.hours) {
                val open = window.withMinute(schedule.minute).toInstant()
                val close = open.plusSeconds(180)
                if (open >= from && open < to) {
                    val fired = submits.any { !it.timestamp.isBefore(open) && it.timestamp.isBefore(close) }
                    val label = "%02d:%02d".format(window.hour, schedule.minute)
                    detail.add(if (fired) "$label FIRED" else "$label MISSED")
                    if (!fired) missed++
                }
            }
            window = window.plusHours(1)
        }
        if (detail.isEmpty()) detail.add("no expected windows in range")
        return GateResult("placement", if (missed == 0) GateStatus.PASS else GateStatus.FAIL, detail)
    }

    /** Gate 2 — fail on any ERROR line (and any non-benign WARN); report up to 10 samples. */
    fun errors(logs: List<LogLine>): GateResult {
        val bad =
            logs.filter { it.isError || (it.level == "WARN" && BENIGN_WARN.none { b -> it.message.contains(b) }) }
        val detail =
            if (bad.isEmpty()) {
                listOf("no engine-side errors")
            } else {
                bad.take(10).map { "${it.timestamp} [${it.level}] ${it.message.take(120)}" }
            }
        return GateResult("errors", if (bad.isEmpty()) GateStatus.PASS else GateStatus.FAIL, detail)
    }

    /**
     * Gate 3 — report cumulative + per-UTC-day realized (reconstructed from `trade … realized=` lines)
     * and check `Σ fills.realized ≈ statusRealized`. The value never auto-fails (a red day is the operator's
     * call); only a reconciliation MISMATCH fails, since it signals a P&L-accounting bug.
     */
    fun pnl(
        logs: List<LogLine>,
        statusRealized: BigDecimal,
        statusUnrealized: BigDecimal,
    ): GateResult {
        val fills = logs.mapNotNull { l -> l.realized?.let { l.timestamp to it } }
        val byDay =
            fills
                .groupBy { it.first.atZone(ZoneOffset.UTC).toLocalDate() }
                .toSortedMap()
                .map { (day, ts) -> "$day realized=${ts.sumOf { it.second }}" }
        val sum = fills.sumOf { it.second }
        val consistent = (sum - statusRealized).abs() <= BigDecimal("0.01")
        val detail = mutableListOf("cumulative realized=$statusRealized unrealized=$statusUnrealized")
        detail.addAll(byDay)
        detail.add(
            if (consistent) {
                "Σfills=$sum consistent with status realized"
            } else {
                "Σfills=$sum MISMATCH vs status realized=$statusRealized"
            },
        )
        return GateResult("pnl", if (consistent) GateStatus.REVIEW else GateStatus.FAIL, detail)
    }
}
