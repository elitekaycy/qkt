package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.report.BacktestReportWriter
import com.qkt.backtest.sweep.SweepReplay
import com.qkt.backtest.sweep.SweepRun
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.EvidenceJson
import com.qkt.evidence.ExperimentEvidence
import com.qkt.evidence.PromotionEvidence
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter

/** `qkt experiment run --plan research-plan.yaml` — governed train/validation/test research run. */
class ExperimentCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
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
                BacktestContext.build(contextArgs(strategyPath, plan, datasetPath), ast, fetcherOverride)
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
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
            trainRanked = runSweep(ctx, combos, trainRange, rank, parallelism)
            val validationCombos = trainRanked.take(topN).map { it.config }
            validationRanked = runSweep(ctx, validationCombos, validationRange, rank, parallelism)
            selected = validationRanked.firstOrNull() ?: return userError("validation produced no candidate")
            testRaw = ctx.backtest(selected.config.overrides, testRange).run()
        } catch (e: IllegalArgumentException) {
            return userError(e.message ?: "experiment run failed")
        } catch (e: IllegalStateException) {
            return userError(e.message ?: "experiment run failed")
        }
        val unstableWarning =
            ResearchGovernance.unstableNeighborhoodWarning(
                trainRanked,
                plan.parameterGrid,
                rank,
            )
        val unstableWarnings = if (unstableWarning != null) listOf(unstableWarning) else emptyList()
        val datasetWarnings =
            if (datasetPath == null) {
                listOf("experiment plan did not pin a dataset snapshot; reproducibility is weaker.")
            } else {
                emptyList()
            }
        val warnings =
            ResearchGovernance.largeSearchWarnings(combos.size, plan.selection.largeSearchThreshold) +
                unstableWarnings +
                datasetWarnings

        val testResult =
            attachEvidence(
                result = testRaw,
                strategyPath = strategyPath,
                executionConfig = ctx.executionConfig,
                datasetEvidence = ctx.datasetEvidence,
                plan = plan,
                selected = selected,
                trialCount = combos.size,
                warnings = warnings,
            )

        val outDir = outputDir(plan)
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

        val registryDir =
            args.option("registry-dir")?.let(Path::of)
                ?: UserDirs().stateHome().resolve("experiments")
        val persisted =
            ExperimentRegistry(registryDir)
                .write(
                    recordBodyJson(
                        runAt = Instant.now(),
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

    private fun contextArgs(
        strategyPath: Path,
        plan: ExperimentPlan,
        datasetPath: String?,
    ): Args {
        val fullFrom = plan.splits.train.from
        val fullTo = plan.splits.test.to
        val tokens =
            mutableListOf(
                "backtest",
                strategyPath.toString(),
                "--from",
                fullFrom.toString(),
                "--to",
                fullTo.toString(),
            )

        fun passOption(name: String) {
            args.option(name)?.let {
                tokens.add("--$name")
                tokens.add(it)
            }
        }
        for (name in listOf("data-root", "config", "broker", "instruments", "starting-balance")) {
            passOption(name)
        }
        datasetPath?.let {
            tokens.add("--dataset")
            tokens.add(it)
        }
        for (flag in listOf("bars", "no-fetch", "allow-incomplete")) {
            if (args.flag(flag)) tokens.add("--$flag")
        }
        return Args(tokens.toTypedArray())
    }

    private fun runSweep(
        ctx: BacktestContext,
        combos: List<ParamGrid.Combo>,
        range: TimeRange,
        rank: RankMetric,
        parallelism: Int,
    ): List<SweepRun<ParamGrid.Combo>> =
        SweepReplay(
            configs = combos.map { it.label to it },
            sharedFeed = { ctx.backtest(emptyMap(), range).detachFeed() },
            engineFor = { _, combo ->
                ctx.backtest(combo.overrides, range).toEngine(SequenceTickFeed(emptySequence()))
            },
            parallelism = parallelism,
        ).run().rankedBy { rank.score(it) }

    private fun attachEvidence(
        result: BacktestResult,
        strategyPath: Path,
        executionConfig: com.qkt.backtest.ExecutionSimulationConfig,
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
                    command = args.tokens,
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

    private fun stageSummaryJson(
        split: String,
        rank: RankMetric,
        ranked: List<SweepRun<ParamGrid.Combo>>,
    ): String =
        buildString {
            append("{\"split\":").append(EvidenceJson.jsonString(split))
            append(",\"rank\":").append(EvidenceJson.jsonString(rank.flag))
            append(",\"trialCount\":").append(ranked.size)
            append(",\"runs\":[")
            append(ranked.joinToString(",") { runJson(it, rank) })
            append("]}")
        }

    private fun recordBodyJson(
        runAt: Instant,
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
            append(",\"command\":").append(ResearchGovernance.stringListJson(args.tokens))
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

    private fun runsJson(
        ranked: List<SweepRun<ParamGrid.Combo>>,
        rank: RankMetric,
    ): String = ranked.joinToString(",", prefix = "[", postfix = "]") { runJson(it, rank) }

    private fun runJson(
        run: SweepRun<ParamGrid.Combo>,
        rank: RankMetric,
    ): String =
        buildString {
            append("{\"label\":").append(EvidenceJson.jsonString(run.label))
            append(",\"params\":").append(ResearchGovernance.stringMapJson(run.config.overrides))
            append(",\"metrics\":").append(ResearchGovernance.runMetricsJson(run.result.global, rank))
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

    private fun userError(message: String): Int {
        System.err.println("qkt: error: $message")
        return ExitCodes.USER_ERROR
    }
}
