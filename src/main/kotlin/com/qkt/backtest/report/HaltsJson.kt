package com.qkt.backtest.report

import com.qkt.events.RiskEvent

/**
 * The risk halts of a run for `result.json`: when trading stopped, for which strategy (null for the
 * whole account), how long it holds (`TRANSIENT`, `DAILY`, `PERSISTENT`) and why, in the order they fired.
 */
internal object HaltsJson {
    /** [halts] as a JSON array. */
    fun render(halts: List<RiskEvent.Halted>): String =
        halts.joinToString(",", prefix = "[", postfix = "]") { h ->
            "{\"timestampMs\": ${h.timestamp}, \"strategyId\": ${text(h.strategyId)}, " +
                "\"scope\": ${text(h.scope)}, \"reason\": ${text(h.reason)}}"
        }

    private fun text(s: String?): String = s?.let(ReportSerializer::jsonString) ?: "null"
}
