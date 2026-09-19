package com.qkt.cli

import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.promotion.PromotionOptions
import com.qkt.cli.promotion.promotionStatus
import java.time.Instant

/** `qkt promotion ...` records and evaluates strategy promotion evidence. */
class PromotionCommand(
    private val args: Args,
) {
    private val options = PromotionOptions(args)

    /** Execute the requested promotion action and return a process exit code. */
    fun run(): Int =
        when (val action = args.firstNonOption()) {
            "record" -> record(withApproval = false)
            "approve" -> record(withApproval = true)
            "waive" -> waive()
            "status" -> promotionStatus(args, options)
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
        val path = options.strategyPath(args.requirePositional(1, "<strategy.qkt>"))
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
        val config = options.promotionConfig()
        val store = options.promotionStore(config)
        val existing = store.latest(name, strategyHash)
        val evidence = existing?.evidence.orEmpty() + options.parseEvidence()
        val paper = options.parsePaper(existing?.paper)
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
        val path = options.strategyPath(args.requirePositional(1, "<strategy.qkt>"))
        val name = args.option("as") ?: path.fileName.toString().removeSuffix(".qkt")
        val reason =
            args.requireOption("reason").takeIf { it.isNotBlank() }
                ?: throw ArgError("--reason must not be blank")
        val gates =
            options
                .parseCsv(args.options("gate"))
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
        val config = options.promotionConfig()
        val store = options.promotionStore(config)
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
