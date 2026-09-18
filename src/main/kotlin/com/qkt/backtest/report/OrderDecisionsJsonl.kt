package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.execution.OrderRequestEvidence

/**
 * The `orders.jsonl` artifact: every approved and rejected order decision as one JSON line,
 * ordered by event sequence id and then timestamp.
 */
internal object OrderDecisionsJsonl {
    fun render(result: BacktestResult): String {
        val lines =
            buildList {
                result.causality?.approvedOrders.orEmpty().forEach { event ->
                    add(
                        Triple(
                            event.sequenceId,
                            event.timestamp,
                            buildString {
                                append("{\"schema\":\"qkt-order-decision-v1\",\"schemaVersion\":1")
                                append(",\"decision\":\"approved\",\"seq\":").append(event.sequenceId)
                                append(",\"ts\":").append(event.timestamp)
                                append(",\"requestSchemaVersion\":").append(OrderRequestEvidence.SCHEMA_VERSION)
                                append(",\"request\":").append(OrderRequestEvidence.toJson(event.request))
                                append('}')
                            },
                        ),
                    )
                }
                result.rejections.forEach { event ->
                    add(
                        Triple(
                            event.sequenceId,
                            event.timestamp,
                            buildString {
                                append("{\"schema\":\"qkt-order-decision-v1\",\"schemaVersion\":1")
                                append(",\"decision\":\"rejected\",\"seq\":").append(event.sequenceId)
                                append(",\"ts\":").append(event.timestamp)
                                append(",\"reason\":").append(ReportSerializer.jsonString(event.reason))
                                append(",\"requestSchemaVersion\":").append(OrderRequestEvidence.SCHEMA_VERSION)
                                append(",\"request\":").append(OrderRequestEvidence.toJson(event.request))
                                append('}')
                            },
                        ),
                    )
                }
            }.sortedWith(compareBy<Triple<Long, Long, String>> { it.first }.thenBy { it.second })
        if (lines.isEmpty()) return ""
        return lines.joinToString(separator = "\n", postfix = "\n") { it.third }
    }
}
