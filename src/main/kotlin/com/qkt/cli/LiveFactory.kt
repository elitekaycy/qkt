package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.ChannelConfig
import com.qkt.notify.ChannelRegistry
import com.qkt.notify.CompositeNotifier
import com.qkt.notify.FilteringNotifier
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind

/**
 * The [StrategyHandle.RealFactory] the config describes: every risk cap, drawdown halt, collar, runaway
 * breaker, margin floor, measured-usage window, journal, persistor, notifier and insights setting the
 * daemon trades with. `qkt daemon` and a live `qkt bot session` both build theirs here, so the two cannot
 * drift apart.
 */
internal fun liveFactory(
    cfg: Config,
    stateDir: StateDir,
    marketSourceProvider: (List<String>) -> MarketSource,
    brokerFactories: Map<String, com.qkt.broker.BrokerFactory>,
    instrumentRegistry: com.qkt.instrument.InstrumentRegistry?,
    calendarFor: (String) -> com.qkt.common.TradingCalendar,
    persistor: com.qkt.persistence.StatePersistor,
    notifier: Notifier,
    notifyEvents: Set<NotifyEventKind>,
    insightsSink: com.qkt.observe.insights.InsightsSink?,
    insightsDeployedIds: () -> Collection<String>,
    insightsSharedDeals: com.qkt.observe.insights.SharedDealFetch,
): StrategyHandle.RealFactory =
    StrategyHandle.RealFactory(
        stateDir = stateDir,
        marketSourceProvider = marketSourceProvider,
        brokerFactories = brokerFactories,
        instrumentRegistry = instrumentRegistry,
        calendarFor = calendarFor,
        maxDailyLoss = cfg.maxDailyLoss,
        perStrategyRisk = cfg.perStrategyRisk,
        maxDrawdownPct = cfg.maxDrawdownPct,
        maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
        totalDdBasis = cfg.totalDdBasis,
        dailyDdBasis = cfg.dailyDdBasis,
        startingBalance = cfg.startingBalance,
        maxOrderQty = cfg.maxOrderQty,
        maxOrderNotional = cfg.maxOrderNotional,
        priceCollarFrac = cfg.priceCollarFrac,
        runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
        runawayMaxRejections = cfg.runawayMaxRejections,
        candleCloseGraceMs = cfg.candleCloseGraceMs,
        accountingConfig = cfg.accountingConfig,
        liveEquityBasis = cfg.liveEquityBasis,
        marginFloorPct = cfg.marginFloorPct,
        measuredUsageHours = cfg.measuredUsageHours,
        measuredUsageMaxQty = cfg.measuredUsageMaxQty,
        journalRoot = stateDir.stateRoot.resolve("journal"),
        auditJournalRoot = stateDir.stateRoot.resolve("audit-journal"),
        persistor = persistor,
        notifier = notifier,
        notifyEvents = notifyEvents,
        insightsSink = insightsSink,
        insightsEvents = cfg.insights.events,
        insightsDeployedIds = insightsDeployedIds,
        insightsStatePollMs = cfg.insights.statePollMs,
        insightsSharedDeals = insightsSharedDeals,
        insightsDealBackfillDays = cfg.insights.dealBackfillDays,
        marketDataGateConfig = cfg.marketData,
    )

/**
 * The notifier the config's `notify:` channels describe, filtered per channel, and the event kinds any of
 * them wants. Unknown channel types are warned about and skipped.
 */
internal fun configNotifier(cfg: Config): NotifySetup {
    val channelRegistry = ChannelRegistry.DEFAULT
    val channelNotifiers: List<Pair<ChannelConfig, Notifier>> =
        cfg.notify.enabledChannels().mapNotNull { ch ->
            val provider = channelRegistry.get(ch.type)
            if (provider == null) {
                println("[WARN] unknown notify channel type: ${ch.type}")
                null
            } else {
                ch to provider.notifier(ch)
            }
        }
    val notifyEventKinds = cfg.notify.enabledEventKinds()
    val notifier: Notifier =
        CompositeNotifier(channelNotifiers.map { (ch, n) -> FilteringNotifier(ch.events, n) })
    return NotifySetup(channelNotifiers, notifier, notifyEventKinds)
}

/** The configured notify channels, the filtered notifier over them, and the event kinds any of them wants. */
internal data class NotifySetup(
    val channels: List<Pair<ChannelConfig, Notifier>>,
    val notifier: Notifier,
    val events: Set<NotifyEventKind>,
)
