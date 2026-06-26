package com.qkt.cli.daemon

import com.qkt.common.SystemClock
import com.qkt.observe.OrderJournal

class OperatorJournal(
    stateDir: StateDir,
    private val source: String,
) {
    private val journal = OrderJournal(stateDir.stateRoot.resolve("journal"), SystemClock())

    fun record(
        action: String,
        target: Target?,
        result: ControlResult,
        details: Map<String, String?> = emptyMap(),
    ) {
        record(
            action = action,
            target = targetLabel(target),
            affected = result.affected,
            unknown = result.unknown,
            outcome = if (result.unknown.isEmpty()) "accepted" else "rejected",
            details = details,
        )
    }

    fun record(
        action: String,
        target: String?,
        affected: List<String>,
        unknown: List<String> = emptyList(),
        outcome: String = "accepted",
        details: Map<String, String?> = emptyMap(),
    ) {
        val targetLabel = target?.ifBlank { null } ?: "_daemon"
        val rows =
            affected.ifEmpty {
                listOf(
                    when {
                        targetLabel == "all" -> "_operator"
                        targetLabel == "_daemon" -> "_operator"
                        else -> targetLabel
                    },
                )
            }
        val fields =
            mutableMapOf<String, String?>(
                "action" to action,
                "target" to targetLabel,
                "source" to source,
                "outcome" to outcome,
                "affected" to affected.joinToString(","),
                "unknown" to unknown.joinToString(","),
            )
        fields.putAll(details)
        for (strategyId in rows) {
            journal.append(strategyId, "operator_action", fields)
        }
    }

    private fun targetLabel(target: Target?): String =
        when (target) {
            null -> "_daemon"
            Target.All -> "all"
            is Target.Strategy -> target.name
        }

    companion object {
        fun from(
            stateDir: StateDir?,
            source: String,
        ): OperatorJournal? = stateDir?.let { OperatorJournal(it, source) }
    }
}
