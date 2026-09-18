package com.qkt.cli.experiment

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.BuildInfo
import com.qkt.cli.ExperimentPlan
import com.qkt.cli.ParamGrid
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.ExperimentEvidence
import com.qkt.evidence.PromotionEvidence
import java.nio.file.Path

/** Stamps the held-out test result with the evidence envelope: build, command, inputs and the selection. */
internal fun attachExperimentEvidence(
    result: BacktestResult,
    command: List<String>,
    strategyPath: Path,
    executionConfig: ExecutionSimulationConfig,
    datasetEvidence: DatasetEvidence,
    plan: ExperimentPlan,
    selected: SweepRun<ParamGrid.Combo>,
    trialCount: Int,
    warnings: List<String>,
): BacktestResult =
    result.copy(
        evidence =
            EvidenceEnvelope(
                qktVersion = BuildInfo.VERSION,
                gitSha = BuildInfo.GIT_SHA,
                buildTimestamp = BuildInfo.BUILD_TIMESTAMP,
                command = command,
                strategyHash = EvidenceHasher.sha256(strategyPath),
                dataset = datasetEvidence,
                execution = executionConfig.toEvidence(),
                experiment =
                    ExperimentEvidence(
                        id = plan.name,
                        trialCount = trialCount,
                        primaryMetric = plan.primaryMetric,
                        splits = plan.splits.asEvidenceMap(),
                        selectedLabel = selected.label,
                        selectedParams = selected.config.overrides,
                        warnings = warnings,
                    ),
                promotion =
                    plan.promotion?.let {
                        PromotionEvidence(
                            state = it.state,
                            rationale = it.rationale,
                        )
                    },
                warnings = warnings,
            ),
    )
