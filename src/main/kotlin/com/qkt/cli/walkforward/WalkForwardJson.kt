package com.qkt.cli.walkforward

import com.qkt.backtest.walkforward.WalkForwardResult
import com.qkt.cli.CliEvidenceJson
import com.qkt.cli.RankMetric
import com.qkt.cli.ResearchGovernance
import com.qkt.evidence.DatasetEvidence
import java.math.BigDecimal

/** Prints the walk-forward result as one JSON object with per-fold detail and winner stability. */
internal fun printWalkForwardJson(
    result: WalkForwardResult<*>,
    rank: RankMetric,
    meanIs: BigDecimal?,
    meanOos: BigDecimal?,
    dataset: DatasetEvidence,
    trialCount: Int,
    warnings: List<String>,
) {
    fun num(v: BigDecimal?): String = v?.toPlainString() ?: "null"

    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val datasetField = CliEvidenceJson.pinnedDataset(dataset)?.let { """"dataset":$it,""" } ?: ""
    val provenanceJson = ResearchGovernance.metricProvenanceJson("walkforward", rank, trialCount)
    val warningsJson = ResearchGovernance.warningListJson(warnings)
    val stability = result.winnerCounts.entries.joinToString(",") { "\"${esc(it.key)}\":${it.value}" }
    val folds =
        result.folds.joinToString(",") { f ->
            """{"train":"${f.trainRange.from}..${f.trainRange.to}",""" +
                """"test":"${f.testRange.from}..${f.testRange.to}",""" +
                """"winner":"${esc(f.winnerLabel)}",""" +
                """"inSample":${num(rank.defined(f.trainScore))},""" +
                """"outOfSample":${num(rank.valueOf(f.testResult.global))},""" +
                """"testTotalPnL":${num(f.testResult.global.totalPnL)},""" +
                """"testMaxDrawdown":${num(f.testResult.global.maxDrawdown)},""" +
                """"testTrades":${f.testResult.trades.size}}"""
        }
    println(
        """{"rank":"${rank.flag}",$datasetField"trialCount":$trialCount,""" +
            """"metricProvenance":$provenanceJson,"selectionWarnings":$warningsJson,""" +
            """"folds":${result.folds.size},""" +
            """"meanInSample":${num(meanIs)},"meanOutOfSample":${num(meanOos)},""" +
            """"winnerStability":{$stability},"foldDetail":[$folds]}""",
    )
}
