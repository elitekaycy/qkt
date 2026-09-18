package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot

/**
 * The `accounting` section of `result.json`: account currency, FX policy and source, the
 * configured and observed conversions, warnings, and the cost kinds the run modeled.
 */
internal object AccountingJson {
    fun render(snapshot: AccountingSnapshot?): String {
        if (snapshot == null) return "null"
        return buildString {
            append("{\"accountCurrency\": ")
                .append(ReportSerializer.jsonString(snapshot.accountCurrency))
            append(", \"missingPolicy\": ")
                .append(ReportSerializer.jsonString(snapshot.missingPolicy))
            append(", \"source\": ")
                .append(ReportSerializer.jsonString(snapshot.source))
            append(", \"configuredSymbols\": {")
            append(
                snapshot.configuredSymbols.entries.sortedBy { it.key }.joinToString(",") {
                    "${ReportSerializer.jsonString(it.key)}: ${ReportSerializer.jsonString(it.value)}"
                },
            )
            append("}, \"conversions\": [")
            append(
                snapshot.conversions.joinToString(",") {
                    "{\"from\": ${ReportSerializer.jsonString(it.from)}, " +
                        "\"to\": ${ReportSerializer.jsonString(it.to)}, " +
                        "\"rate\": ${ReportSerializer.jsonBigDecimal(it.rate)}, " +
                        "\"timestamp\": ${it.timestamp}, " +
                        "\"source\": ${ReportSerializer.jsonString(it.source)}}"
                },
            )
            append("], \"warnings\": [")
            append(snapshot.warnings.joinToString(",") { ReportSerializer.jsonString(it) })
            append("], \"costKinds\": [")
            append(snapshot.supportedCostKinds.joinToString(",") { ReportSerializer.jsonString(it) })
            append("]}")
        }
    }
}
