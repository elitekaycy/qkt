package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.IncompleteDataException
import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.experiment.attachExperimentEvidence
import com.qkt.cli.experiment.experimentContextArgs
import com.qkt.cli.experiment.experimentWarnings
import com.qkt.cli.experiment.recordBodyJson
import com.qkt.cli.experiment.runExperimentSweep
import com.qkt.cli.experiment.writeExperimentArtifacts
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

/** `qkt experiment run --plan research-plan.yaml` — governed train/validation/test research run. */
class ExperimentCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
    /** Run the plan's train sweep, validation re-rank and single test pass, then record the experiment. */
    fun run(): Int {
        if (args.firstNonOption() != "run") {
            System.err.println("qkt: error: usage: qkt experiment run --plan <research-plan.yaml>")
            return ExitCodes.ARG_ERROR
        }
        val planPath = Path.of(args.requireOption("plan"))
        val plan =
            try {
                ExperimentPlanLoader.load(planPath)
            } catch (e: Exception) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val strategyPath = Path.of(args.option("strategy") ?: plan.strategy ?: "")
        if (strategyPath.toString().isBlank()) {
            System.err.println("qkt: error: strategy must be set in the plan or with --strategy")
            return ExitCodes.USER_ERROR
        }
        if (!Files.exists(strategyPath)) {
            System.err.println("qkt: error: strategy file not found: $strategyPath")
            return ExitCodes.USER_ERROR
        }

        val rank = RankMetric.fromFlag(plan.primaryMetric)
        val combos = ParamGrid.expand(plan.parameterGrid)
        val topN = plan.selection.topN.coerceAtMost(combos.size)
        val parallelism = args.option("parallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val datasetPath = args.option("dataset") ?: plan.dataset

        val ast =
            when (val parsed = Dsl.parseFile(strategyPath)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$strategyPath:${e.line}:${e.col} — ${e.message}")
                    return ExitCodes.USER_ERROR
                }
            }

        val ctx =
            try {
                BacktestContext.build(
                    experimentContextArgs(args, strategyPath, plan, datasetPath),
                    ast,
                    fetcherOverride,
                )
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        try {
            ctx.provision()
        } catch (e: IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        val trainRange = TimeRange(plan.splits.train.from, plan.splits.train.to)
        val validationRange = TimeRange(plan.splits.validation.from, plan.splits.validation.to)
        val testRange = TimeRange(plan.splits.test.from, plan.splits.test.to)
        val trainRanked: List<SweepRun<ParamGrid.Combo>>
        val validationRanked: List<SweepRun<ParamGrid.Combo>>
        val selected: SweepRun<ParamGrid.Combo>
        val testRaw: BacktestResult
        try {
            trainRanked = runExperimentSweep(ctx, combos, trainRange, rank, parallelism)
            val validationCombos = trainRanked.take(topN).map { it.config }
            validationRanked = runExperimentSweep(ctx, validationCombos, validationRange, rank, parallelism)
            selected = validationRanked.firstOrNull() ?: return userError("validation produced no candidate")
            testRaw = ctx.backtest(selected.config.overrides, testRange).run()
        } catch (e: IllegalArgumentException) {
            return userError(e.message ?: "experiment run failed")
        } catch (e: IllegalStateException) {
            return userError(e.message ?: "experiment run failed")
        }
        val warnings = experimentWarnings(plan, combos.size, trainRanked, rank, datasetPath)

        val testResult =
            attachExperimentEvidence(
                result = testRaw,
                command = args.tokens,
                strategyPath = strategyPath,
                executionConfig = ctx.executionConfig,
                datasetEvidence = ctx.datasetEvidence,
                plan = plan,
                selected = selected,
                trialCount = combos.size,
                warnings = warnings,
            )

        val outDir = outputDir(plan)
        val testReportDir =
            writeExperimentArtifacts(outDir, planPath, rank, trainRanked, validationRanked, testResult)

        val registryDir =
            args.option("registry-dir")?.let(Path::of)
                ?: UserDirs().stateHome().resolve("experiments")
        val persisted =
            ExperimentRegistry(registryDir)
                .write(
                    recordBodyJson(
                        runAt = Instant.now(),
                        command = args.tokens,
                        planPath = planPath,
                        plan = plan,
                        strategyPath = strategyPath,
                        datasetPath = datasetPath?.let(Path::of),
                        rank = rank,
                        trainRanked = trainRanked,
                        validationRanked = validationRanked,
                        selected = selected,
                        testResult = testResult,
                        warnings = warnings,
                        outDir = outDir,
                        testReportDir = testReportDir,
                    ),
                )

        if (args.flag("json")) {
            println(persisted.json)
        } else {
            println("experiment: ${plan.name}")
            println("id: ${persisted.id}")
            println("selected: ${selected.label} ${selected.config.overrides}")
            println("record: ${persisted.objectPath}")
            println("index: ${persisted.indexPath}")
            for (warning in warnings) println("warning: $warning")
        }
        return ExitCodes.SUCCESS
    }

    private fun outputDir(plan: ExperimentPlan): Path {
        args.option("out-dir")?.let { return Path.of(it) }
        val safeName = plan.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val rawStamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val stamp = rawStamp.replace(":", "").replace(".", "")
        val registryDir =
            args.option("registry-dir")?.let(Path::of)
                ?: UserDirs().stateHome().resolve("experiments")
        return registryDir.resolve("runs").resolve(safeName).resolve(stamp)
    }

    private fun userError(message: String): Int {
        System.err.println("qkt: error: $message")
        return ExitCodes.USER_ERROR
    }
}
