package com.qkt.cli.experiment

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.BuildInfo
import com.qkt.cli.ExperimentPlan
import com.qkt.cli.ExperimentPromotionPlan
import com.qkt.cli.ParamGrid
import com.qkt.cli.RankMetric
import com.qkt.cli.ResearchGovernance
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.EvidenceJson
import java.nio.file.Path
import java.time.Instant

/**
 * The experiment registry record body: build and command, hashed plan/strategy/dataset inputs,
 * splits, grid, selection, warnings, report locations and per-stage metrics.
 */
internal fun recordBodyJson(
    runAt: Instant,
    command: List<String>,
    planPath: Path,
    plan: ExperimentPlan,
    strategyPath: Path,
    datasetPath: Path?,
    rank: RankMetric,
    trainRanked: List<SweepRun<ParamGrid.Combo>>,
    validationRanked: List<SweepRun<ParamGrid.Combo>>,
    selected: SweepRun<ParamGrid.Combo>,
    testResult: BacktestResult,
    warnings: List<String>,
    outDir: Path,
    testReportDir: Path,
): String =
    buildString {
        append("{\"runAt\":").append(EvidenceJson.jsonString(runAt.toString()))
        append(",\"qkt\":{")
        append("\"version\":").append(EvidenceJson.jsonString(BuildInfo.VERSION))
        append(",\"gitSha\":").append(EvidenceJson.jsonString(BuildInfo.GIT_SHA))
        append(",\"buildTimestamp\":").append(EvidenceJson.jsonString(BuildInfo.BUILD_TIMESTAMP))
        append("}")
        append(",\"command\":").append(ResearchGovernance.stringListJson(command))
        append(",\"plan\":{")
        append("\"path\":").append(EvidenceJson.jsonString(planPath.toString()))
        append(",\"hash\":").append(EvidenceJson.jsonString(EvidenceHasher.sha256(planPath)))
        append(",\"name\":").append(EvidenceJson.jsonString(plan.name))
        append(",\"objective\":").append(nullableString(plan.objective))
        append("}")
        append(",\"strategy\":{")
        append("\"path\":").append(EvidenceJson.jsonString(strategyPath.toString()))
        append(",\"hash\":").append(EvidenceJson.jsonString(EvidenceHasher.sha256(strategyPath)))
        append(",\"imports\":{}")
        append("}")
        append(",\"dataset\":").append(datasetJson(datasetPath, testResult))
        append(",\"splits\":").append(ResearchGovernance.stringMapJson(plan.splits.asEvidenceMap()))
        append(",\"metrics\":{")
        append("\"primary\":").append(EvidenceJson.jsonString(rank.flag))
        append(",\"secondary\":").append(ResearchGovernance.stringListJson(plan.secondaryMetrics))
        append("}")
        append(",\"constraints\":").append(ResearchGovernance.stringMapJson(plan.constraints))
        append(",\"parameterGrid\":").append(gridJson(plan.parameterGrid))
        append(",\"seed\":").append(plan.seed?.toString() ?: "null")
        append(",\"trialCount\":").append(trainRanked.size)
        append(",\"selection\":{")
        append("\"method\":").append(EvidenceJson.jsonString(plan.selection.method))
        append(",\"topN\":").append(plan.selection.topN)
        append(",\"selectedBy\":").append(EvidenceJson.jsonString("validation.${rank.flag}"))
        append(",\"selectedLabel\":").append(EvidenceJson.jsonString(selected.label))
        append(",\"selectedParams\":").append(ResearchGovernance.stringMapJson(selected.config.overrides))
        append("}")
        append(",\"promotion\":").append(promotionJson(plan.promotion))
        append(",\"warnings\":").append(ResearchGovernance.warningListJson(warnings))
        append(",\"reports\":{")
        append("\"directory\":").append(EvidenceJson.jsonString(outDir.toString()))
        append(",\"testReportHtml\":")
            .append(EvidenceJson.jsonString(testReportDir.resolve("report.html").toString()))
        append(",\"testResultJson\":")
            .append(EvidenceJson.jsonString(testReportDir.resolve("result.json").toString()))
        append("}")
        append(",\"stages\":{")
        append("\"train\":").append(runsJson(trainRanked, rank))
        append(",\"validation\":").append(runsJson(validationRanked, rank))
        append(",\"test\":").append(ResearchGovernance.runMetricsJson(testResult.global, rank))
        append("}")
        append("}")
    }

private fun datasetJson(
    datasetPath: Path?,
    result: BacktestResult,
): String {
    val evidence = result.evidence?.dataset
    return buildString {
        append("{")
        append("\"path\":").append(nullableString(datasetPath?.toString()))
        val pathHash =
            datasetPath
                ?.let {
                    EvidenceJson.jsonString(EvidenceHasher.sha256(it))
                } ?: "null"
        append(",\"pathHash\":").append(pathHash)
        append(",\"id\":").append(nullableString(evidence?.id))
        append(",\"hash\":").append(nullableString(evidence?.hash))
        append(",\"qualityPolicy\":").append(nullableString(evidence?.qualityPolicy))
        append(",\"mutableStore\":").append(evidence?.mutableStore ?: true)
        append("}")
    }
}

private fun gridJson(grid: Map<String, List<String>>): String =
    grid.entries
        .sortedBy { it.key }
        .joinToString(",", prefix = "{", postfix = "}") { (name, values) ->
            "${EvidenceJson.jsonString(name)}:${ResearchGovernance.stringListJson(values)}"
        }

private fun promotionJson(promotion: ExperimentPromotionPlan?): String {
    if (promotion == null) return "null"
    return buildString {
        append("{\"state\":").append(nullableString(promotion.state))
        append(",\"rationale\":").append(nullableString(promotion.rationale))
        append("}")
    }
}

private fun nullableString(value: String?): String = value?.let(EvidenceJson::jsonString) ?: "null"
