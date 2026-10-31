package com.qkt.cli.daemon

import com.qkt.cli.metadata
import com.qkt.dsl.StrategySource
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StringLit
import com.qkt.observe.insights.sourceFacts
import com.qkt.observe.insights.streamFacts
import java.nio.file.Path

/** What a deployed strategy's `strategy.started` states about itself: its deploy, parameters, limits, source and config. */
private fun literalPayload(expr: ExprAst): Any =
    when (expr) {
        is NumLit -> expr.value
        is BoolLit -> expr.value
        is StringLit -> expr.value
        else -> expr.toString()
    }

private fun defaultsPayload(defaults: DefaultsBlock?): Map<String, String>? {
    if (defaults == null) return null
    val out = linkedMapOf<String, String>()
    defaults.sizing?.let { out["sizing"] = it::class.simpleName ?: it.toString() }
    defaults.orderType?.let { out["orderType"] = it::class.simpleName ?: it.toString() }
    defaults.tif?.let { out["tif"] = it::class.simpleName ?: it.toString() }
    defaults.stopLoss?.let { out["stopLoss"] = it::class.simpleName ?: it.toString() }
    defaults.takeProfit?.let { out["takeProfit"] = it::class.simpleName ?: it.toString() }
    defaults.trailing?.let { out["trailing"] = it::class.simpleName ?: it.toString() }
    return out.takeIf { it.isNotEmpty() }
}

internal fun StrategyHandle.RealFactory.strategyMetadata(
    deployName: String,
    file: Path,
    ast: StrategyAst,
    symbols: List<String>,
    perStrategyOverride: com.qkt.cli.PerStrategyRisk?,
): Map<String, Any?> =
    linkedMapOf(
        "deployName" to deployName,
        "dslVersion" to ast.version,
        "runtimeMode" to if (brokerFactories.isEmpty()) "paper" else "live",
        "brokers" to brokerFactories.keys.sorted(),
        "symbols" to symbols,
        "streams" to streamFacts(ast.streams),
        "params" to ast.params.associate { it.name to literalPayload(it.value) },
        "defaults" to defaultsPayload(ast.defaults),
        "risk" to
            linkedMapOf(
                "startingBalance" to startingBalance,
                "maxDailyLoss" to maxDailyLoss,
                "maxDrawdownPct" to maxDrawdownPct,
                "maxDailyDrawdownPct" to maxDailyDrawdownPct,
                "totalDdBasis" to totalDdBasis.name,
                "dailyDdBasis" to dailyDdBasis.name,
                "liveEquityBasis" to liveEquityBasis.name,
                "maxOrderQty" to maxOrderQty,
                "maxOrderNotional" to maxOrderNotional,
                "priceCollarFrac" to priceCollarFrac,
                "runawayMaxRoundTrips10m" to runawayMaxRoundTrips,
                "runawayMaxBrokerRejections1m" to runawayMaxRejections,
                "marginFloorPct" to marginFloorPct,
                "measuredUsageHours" to measuredUsageHours,
                "measuredUsageMaxQty" to measuredUsageMaxQty,
                "perStrategyMaxDailyLoss" to perStrategyOverride?.maxDailyLoss,
                "perStrategyMaxPositionSize" to perStrategyOverride?.maxPositionSize,
                "perStrategyMaxOpenPositions" to perStrategyOverride?.maxOpenPositions,
                "perStrategyMaxDrawdownPct" to perStrategyOverride?.maxDrawdownPct,
                "perStrategyMaxDailyDrawdownPct" to perStrategyOverride?.maxDailyDrawdownPct,
                "perStrategyMaxTradesPerDay" to perStrategyOverride?.maxTradesPerDay,
                "perStrategyCooldownAfterLossMs" to perStrategyOverride?.cooldownAfterLossMs,
                "perStrategyCooldownAfterLossAfterConsecutive" to
                    perStrategyOverride?.cooldownAfterLossAfterConsecutive,
                "perStrategyLossStreakHalt" to perStrategyOverride?.lossStreakHalt,
                "perStrategyLossStreakHaltScope" to perStrategyOverride?.lossStreakHaltScope?.name,
            ),
    ) + sourceFacts(StrategySource.read(file)) +
        effectiveConfig.metadata { standalone(symbols, perStrategyOverride) }
