package com.qkt.cli.experiment

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.report.BacktestReportWriter
import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.ParamGrid
import com.qkt.cli.RankMetric
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes a run's output directory: a copy of the plan, the train and validation summaries, and the
 * full backtest report of the test pass under `test/`. Returns that test report directory.
 */
internal fun writeExperimentArtifacts(
    outDir: Path,
    planPath: Path,
    rank: RankMetric,
    trainRanked: List<SweepRun<ParamGrid.Combo>>,
    validationRanked: List<SweepRun<ParamGrid.Combo>>,
    testResult: BacktestResult,
): Path {
    Files.createDirectories(outDir)
    Files.copy(planPath, outDir.resolve("plan.yaml"), StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(outDir.resolve("train_summary.json"), stageSummaryJson("train", rank, trainRanked))
    Files.writeString(
        outDir.resolve("validation_summary.json"),
        stageSummaryJson("validation", rank, validationRanked),
    )
    val testReportDir = outDir.resolve("test")
    Files.createDirectories(testReportDir)
    BacktestReportWriter(testReportDir).write(testResult)
    return testReportDir
}
