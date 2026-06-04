package com.qkt.cli.observe

/** Renders the gate results as a text go/no-go report and computes the overall verdict. */
object ObserveReportRenderer {
    /** GO iff no gate FAILed (placement/errors must PASS; pnl is REVIEW). NO-GO on any FAIL. */
    fun verdict(gates: List<GateResult>): String = if (gates.any { it.status == GateStatus.FAIL }) "NO-GO" else "GO"

    fun text(
        strategy: String,
        period: String,
        gates: List<GateResult>,
    ): String =
        buildString {
            appendLine("qkt observe — $strategy — last $period")
            appendLine("=".repeat(48))
            for (g in gates) {
                appendLine("[${g.status}] ${g.name}")
                for (d in g.detail) appendLine("    $d")
            }
            appendLine("=".repeat(48))
            appendLine("VERDICT: ${verdict(gates)}")
        }
}
