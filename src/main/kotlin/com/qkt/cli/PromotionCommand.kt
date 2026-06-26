package com.qkt.cli

import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** `qkt promotion ...` records and evaluates strategy promotion evidence. */
class PromotionCommand(
    private val args: Args,
) {
    fun run(): Int =
        when (val action = args.firstNonOption()) {
            "record" -> record(withApproval = false)
            "approve" -> record(withApproval = true)
            "waive" -> waive()
            "status" -> status()
            null, "help", "--help" -> {
                printUsage()
                ExitCodes.SUCCESS
            }
            else -> {
                System.err.println("qkt: unknown promotion action '$action'")
                printUsage()
                ExitCodes.ARG_ERROR
            }
        }

    private fun record(withApproval: Boolean): Int {
        val path = strategyPath(args.requirePositional(1, "<strategy.qkt>"))
        val name = args.option("as") ?: path.fileName.toString().removeSuffix(".qkt")
        val state =
            args.option("state")?.let { raw ->
                PromotionState.fromId(raw) ?: throw ArgError("unknown promotion state: $raw")
            } ?: if (withApproval) {
                PromotionState.PRODUCTION
            } else {
                PromotionState.CANDIDATE
            }
        val reason =
            args.requireOption("reason").takeIf { it.isNotBlank() }
                ?: throw ArgError("--reason must not be blank")
        val now = Instant.now()
        val actor = args.option("actor") ?: System.getProperty("user.name", "unknown")
        val strategyHash = PromotionGateEvaluator.strategyHash(path)
        val config = promotionConfig()
        val store = promotionStore(config)
        val existing = store.latest(name, strategyHash)
        val evidence = existing?.evidence.orEmpty() + parseEvidence()
        val paper = parsePaper(existing?.paper)
        val approvals =
            if (withApproval) {
                existing?.approvals.orEmpty() +
                    PromotionApproval(
                        state = state,
                        actor = actor,
                        reason = reason,
                        approvedAt = now.toString(),
                    )
            } else {
                existing?.approvals.orEmpty()
            }
        val record =
            existing
                ?.update(
                    now = now,
                    state = state,
                    rationale = reason,
                    evidence = evidence,
                    paper = paper,
                    approvals = approvals,
                )
                ?: PromotionRecord.create(
                    strategy = name,
                    strategyHash = strategyHash,
                    state = state,
                    rationale = reason,
                    now = now,
                    evidence = evidence,
                    paper = paper,
                    approvals = approvals,
                )
        store.append(record)
        if (args.flag("json")) {
            println(PromotionJson.encode(record))
        } else {
            println("promotion ${if (withApproval) "approved" else "recorded"}: $name ${state.id} $strategyHash")
            if (paper != null) println("paper: ${paper.days} days, ${paper.trades} trades")
        }
        return ExitCodes.SUCCESS
    }

    private fun waive(): Int {
        val path = strategyPath(args.requirePositional(1, "<strategy.qkt>"))
        val name = args.option("as") ?: path.fileName.toString().removeSuffix(".qkt")
        val reason =
            args.requireOption("reason").takeIf { it.isNotBlank() }
                ?: throw ArgError("--reason must not be blank")
        val gates =
            parseCsv(args.options("gate"))
                .ifEmpty {
                    if (args.flag("all")) listOf("all") else throw ArgError("missing required flag --gate")
                }
        val expiresAt =
            args.option("expires")?.also {
                runCatching { Instant.parse(it) }
                    .getOrElse { throw ArgError("--expires must be an ISO-8601 instant") }
            }
        val now = Instant.now()
        val actor = args.option("actor") ?: System.getProperty("user.name", "unknown")
        val strategyHash = PromotionGateEvaluator.strategyHash(path)
        val config = promotionConfig()
        val store = promotionStore(config)
        val existing = store.latest(name, strategyHash)
        val waiver =
            PromotionWaiver(
                gates = gates,
                reason = reason,
                actor = actor,
                createdAt = now.toString(),
                expiresAt = expiresAt,
            )
        val record =
            existing
                ?.update(now = now, waivers = existing.waivers + waiver)
                ?: PromotionRecord.create(
                    strategy = name,
                    strategyHash = strategyHash,
                    state = PromotionState.DRAFT,
                    rationale = "waiver without prior promotion record",
                    now = now,
                    waivers = listOf(waiver),
                )
        store.append(record)
        OperatorJournal
            .from(StateDir.resolve(args.option("state-dir")), "cli")
            ?.record(
                action = "promotion.waive",
                target = name,
                affected = listOf(name),
                details =
                    mapOf(
                        "gates" to gates.joinToString(","),
                        "reason" to reason,
                        "strategyHash" to strategyHash,
                        "expiresAt" to expiresAt,
                    ),
            )
        if (args.flag("json")) {
            println(PromotionJson.encode(record))
        } else {
            println("promotion waiver recorded: $name gates=${gates.joinToString(",")} $strategyHash")
        }
        return ExitCodes.SUCCESS
    }

    private fun status(): Int {
        val subject = args.requirePositional(1, "<strategy|strategy.qkt>")
        val explicitPath = args.option("strategy")?.let(::strategyPath)
        val positionalPath =
            Path
                .of(subject)
                .takeIf { explicitPath == null && (Files.exists(it) || subject.endsWith(".qkt")) }
                ?.let { strategyPath(it.toString()) }
        val path = explicitPath ?: positionalPath
        val name = args.option("as") ?: path?.fileName?.toString()?.removeSuffix(".qkt") ?: subject
        val config = promotionConfig()
        val store = promotionStore(config)
        val result =
            PromotionGateEvaluator(config)
                .evaluate(
                    strategy = name,
                    strategyPath = path,
                    store = store,
                )
        if (args.flag("json")) {
            println(PromotionJson.encode(result))
            return ExitCodes.SUCCESS
        }
        println("strategy: $name")
        println("state: ${result.state ?: "none"}")
        println("hash: ${result.strategyHash ?: "unknown"}")
        println("required: ${result.requiredState}")
        println("enforced: ${if (result.enforced) "yes" else "no"}")
        println("eligible: ${if (result.eligibleForProduction) "yes" else "no"}")
        println("missing gates: ${result.missingGates.ifEmpty { listOf("none") }.joinToString(",")}")
        if (result.waivedGates.isNotEmpty()) println("waived gates: ${result.waivedGates.joinToString(",")}")
        result.paper?.let { paper ->
            println(
                "paper/live validation: ${paper.days} days, ${paper.trades} trades" +
                    ", avg_slippage_bps=${paper.avgSlippageBps ?: "n/a"}" +
                    ", p95_slippage_bps=${paper.p95SlippageBps ?: "n/a"}" +
                    ", rejection_rate_pct=${paper.rejectionRatePct ?: "n/a"}" +
                    ", missed_fills=${paper.missedFills ?: "n/a"}" +
                    ", status=${paper.status ?: "n/a"}",
            )
        }
        return if (result.blocked) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
    }

    private fun promotionConfig(): PromotionGateConfig {
        val cfgPath = args.option("config")?.let(Path::of) ?: Path.of("./qkt.config.yaml")
        return Config.load(cfgPath).promotionGateConfig
    }

    private fun promotionStore(config: PromotionGateConfig): PromotionStore {
        val root =
            args.option("registry-dir")?.let(Path::of)
                ?: config.registryDir
                ?: StateDir.resolve(args.option("state-dir")).stateRoot.resolve("promotion")
        return PromotionStore(root)
    }

    private fun strategyPath(raw: String): Path {
        val path = Path.of(raw).toAbsolutePath()
        if (!Files.exists(path)) throw ArgError("file not found: $raw")
        return path
    }

    private fun parseEvidence(): Map<String, String> =
        parseCsv(args.options("evidence"))
            .associate { item ->
                val i = item.indexOf('=')
                if (i <= 0 || i == item.lastIndex) throw ArgError("--evidence must use key=value")
                item.substring(0, i).trim() to item.substring(i + 1).trim()
            }

    private fun parsePaper(existing: PaperValidationMetrics?): PaperValidationMetrics? {
        val supplied =
            listOf(
                "paper-days",
                "paper-trades",
                "avg-slippage-bps",
                "p95-slippage-bps",
                "rejection-rate-pct",
                "missed-fills",
                "paper-status",
            ).any { args.option(it) != null }
        if (!supplied) return existing
        return PaperValidationMetrics(
            days = intOption("paper-days") ?: existing?.days ?: 0,
            trades = intOption("paper-trades") ?: existing?.trades ?: 0,
            avgSlippageBps = doubleOption("avg-slippage-bps") ?: existing?.avgSlippageBps,
            p95SlippageBps = doubleOption("p95-slippage-bps") ?: existing?.p95SlippageBps,
            rejectionRatePct = doubleOption("rejection-rate-pct") ?: existing?.rejectionRatePct,
            missedFills = intOption("missed-fills") ?: existing?.missedFills,
            status = args.option("paper-status") ?: existing?.status,
        )
    }

    private fun intOption(name: String): Int? =
        args.option(name)?.toIntOrNull()?.takeIf { it >= 0 }
            ?: args.option(name)?.let { throw ArgError("--$name must be a non-negative integer") }

    private fun doubleOption(name: String): Double? =
        args.option(name)?.toDoubleOrNull()?.takeIf { it >= 0.0 }
            ?: args.option(name)?.let { throw ArgError("--$name must be a non-negative number") }

    private fun parseCsv(values: List<String>): List<String> =
        values
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun printUsage() {
        println(
            """
            qkt promotion record <strategy.qkt> --as <name> --state <state> --reason <text>
            qkt promotion approve <strategy.qkt> --as <name> --state production --reason <text>
            qkt promotion waive <strategy.qkt> --as <name> --gate <gate> --reason <text>
            qkt promotion status <name|strategy.qkt> [--strategy <strategy.qkt>]
            """.trimIndent(),
        )
    }
}
