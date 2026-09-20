package com.qkt.cli.promotion

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.PromotionGateEvaluator
import com.qkt.cli.PromotionJson
import java.nio.file.Files
import java.nio.file.Path

/**
 * `qkt promotion status`: evaluates the strategy against the promotion gates and prints the
 * result. Exits with a user error when the gates are enforced and not met.
 */
internal fun promotionStatus(
    args: Args,
    options: PromotionOptions,
): Int {
    val subject = args.requirePositional(1, "<strategy|strategy.qkt>")
    val explicitPath = args.option("strategy")?.let(options::strategyPath)
    val positionalPath =
        Path
            .of(subject)
            .takeIf { explicitPath == null && (Files.exists(it) || subject.endsWith(".qkt")) }
            ?.let { options.strategyPath(it.toString()) }
    val path = explicitPath ?: positionalPath
    val name = args.option("as") ?: path?.fileName?.toString()?.removeSuffix(".qkt") ?: subject
    val config = options.promotionConfig()
    val store = options.promotionStore(config)
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
