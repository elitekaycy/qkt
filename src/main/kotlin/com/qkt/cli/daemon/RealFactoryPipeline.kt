package com.qkt.cli.daemon

import com.qkt.app.LiveSession
import com.qkt.app.LiveSessionHandle
import com.qkt.candles.TimeWindow
import com.qkt.common.TradingCalendar
import com.qkt.execution.Trade
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import java.math.BigDecimal

/**
 * The live trading pipeline a [StrategyHandle.RealFactory] builds, from the factory's config, for
 * [strategies] on [symbols]: what `qkt deploy` runs for a strategy file and a live `qkt bot session`
 * runs for its identities, so both get the same risk caps, halts, collar, journals, persistence,
 * notifier and insights. The MDC carries the DSL strategy id ([mdcStrategy]), not the deploy name:
 * per-strategy files and insights must match the id every trading event uses.
 */
internal fun StrategyHandle.RealFactory.startPipeline(
    strategies: List<Pair<String, Strategy>>,
    mdcStrategy: String,
    symbols: List<String>,
    candleWindow: TimeWindow?,
    calendar: TradingCalendar,
    perStrategyOverride: com.qkt.cli.PerStrategyRisk?,
    metadata: Map<String, Map<String, Any?>>,
    ignoreMismatches: Boolean,
    onTrade: (Trade, BigDecimal, String) -> Unit,
    onSignal: (Signal) -> Unit,
): LiveSessionHandle {
    val haltRules: List<com.qkt.risk.HaltRule> =
        com.qkt.risk.HaltRules.standard(
            maxDailyLoss = maxDailyLoss,
            maxDrawdownPct = maxDrawdownPct,
            maxDailyDrawdownPct = maxDailyDrawdownPct,
            totalDdBasis = totalDdBasis,
            startingBalance = startingBalance,
        )
    val feedSymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()
    return LiveSession(
        strategies = strategies,
        haltRules = haltRules,
        source = marketSourceProvider(feedSymbols),
        symbols = symbols,
        feedSymbols = feedSymbols,
        candleWindow = candleWindow,
        calendar = calendar,
        accountingConfig = accountingConfig,
        equityBasis = liveEquityBasis,
        // The MDC carries the DSL strategy id, not the deploy name: downstream attribution
        // (per-strategy files, insights) must match the id every trading event uses, or
        // consumers see two strategies (deploy "hedge-straddle" vs STRATEGY hedge_straddle).
        mdcStrategy = mdcStrategy,
        onTrade = onTrade,
        onSignal = onSignal,
        brokerFactories = brokerFactories,
        instrumentRegistry = instrumentRegistry,
        persistor = persistor,
        ignoreMismatches = ignoreMismatches,
        notifier = notifier,
        notifyEvents = notifyEvents,
        perStrategyMaxDailyLoss = perStrategyOverride?.maxDailyLoss,
        perStrategyMaxPositionSize = perStrategyOverride?.maxPositionSize,
        perStrategyMaxOpenPositions = perStrategyOverride?.maxOpenPositions,
        perStrategyMaxDrawdownPct = perStrategyOverride?.maxDrawdownPct,
        perStrategyMaxDailyDrawdownPct = perStrategyOverride?.maxDailyDrawdownPct,
        perStrategyMaxTradesPerDay = perStrategyOverride?.maxTradesPerDay,
        perStrategyCooldownAfterLossMs = perStrategyOverride?.cooldownAfterLossMs,
        perStrategyCooldownAfterLossAfterConsecutive =
            perStrategyOverride?.cooldownAfterLossAfterConsecutive ?: 1,
        perStrategyLossStreakHalt = perStrategyOverride?.lossStreakHalt,
        perStrategyLossStreakHaltScope =
            perStrategyOverride?.lossStreakHaltScope ?: com.qkt.risk.HaltScope.PERSISTENT,
        initialBalance = startingBalance,
        totalDdBasis = totalDdBasis,
        dailyDdBasis = dailyDdBasis,
        maxOrderQty = maxOrderQty,
        maxOrderNotional = maxOrderNotional,
        priceCollarFrac = priceCollarFrac,
        runawayMaxRoundTrips = runawayMaxRoundTrips,
        runawayMaxRejections = runawayMaxRejections,
        candleCloseGraceMs = candleCloseGraceMs,
        marginFloorPct = marginFloorPct,
        measuredUsageHours = measuredUsageHours,
        measuredUsageMaxQty = measuredUsageMaxQty,
        journal =
            journalRoot?.let {
                com.qkt.observe.OrderJournal(it, com.qkt.common.SystemClock())
            },
        auditJournal =
            auditJournalRoot?.let {
                com.qkt.observe.EngineAuditJournal(it, mdcStrategy, com.qkt.common.SystemClock())
            },
        insightsSink = insightsSink,
        insightsEvents = insightsEvents,
        insightsDeployedIds = insightsDeployedIds,
        insightsStrategyMetadata = metadata,
        insightsStatePollMs = insightsStatePollMs,
        insightsSharedDeals = insightsSharedDeals,
        insightsDealBackfillDays = insightsDealBackfillDays,
        marketDataGateConfig = marketDataGateConfig,
    ).start()
}

/**
 * A live `qkt bot session`'s pipeline over [strategies] (its identities and its bar recorder), built from
 * the same factory config as a deploy. `per_strategy_risk` binds one strategy per session, so it applies
 * to a session's only identity and is refused when several are declared and any of them has an entry.
 */
internal fun StrategyHandle.RealFactory.createLiveSession(
    name: String,
    strategies: List<Pair<String, Strategy>>,
    symbols: List<String>,
    candleWindow: TimeWindow?,
    ignoreMismatches: Boolean,
    onTrade: (Trade, BigDecimal, String) -> Unit,
): LiveSessionHandle {
    val identities = strategies.map { it.first }.filterNot { it.startsWith("__") }
    val risked = identities.filter { it in perStrategyRisk }
    require(identities.size == 1 || risked.isEmpty()) {
        "per_strategy_risk binds one identity per session; run ${risked.joinToString()} in a session of its own"
    }
    val metadata =
        identities.associateWith {
            mapOf("deployName" to name, "kind" to "bot-session", "symbols" to symbols, "runtimeMode" to "live")
        }
    return startPipeline(
        strategies = strategies,
        mdcStrategy = name,
        symbols = symbols,
        candleWindow = candleWindow,
        calendar = com.qkt.cli.strategyCalendar(symbols, instrumentRegistry, calendarFor),
        perStrategyOverride = identities.singleOrNull()?.let { perStrategyRisk[it] },
        metadata = metadata,
        ignoreMismatches = ignoreMismatches,
        onTrade = onTrade,
        onSignal = {},
    )
}
