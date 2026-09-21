package com.qkt.app

import com.qkt.accounting.ConvertedMoney
import com.qkt.backtest.FillState
import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.execution.Trade
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.observe.insights.InsightsTranslate
import com.qkt.persistence.PersistenceHealth
import com.qkt.strategy.Strategy

/**
 * The insights envelopes a live session emits itself rather than from a bus event: strategy and
 * feed lifecycle, stale market data, closed trades and persistence health. Every emit is a no-op
 * without a sink or with its event family disabled, e.g. a session with only `TRADE` enabled
 * offers `trade.closed` but never `strategy.started`.
 */
internal class InsightsLifecycle(
    private val insightsSink: InsightsSink?,
    private val insightsEvents: Set<InsightsEventFamily>,
    private val insightsStrategyMetadata: Map<String, Map<String, Any?>>,
    private val strategies: List<Pair<String, Strategy>>,
    private val source: MarketSource,
    private val feedSymbols: List<String>,
    private val clock: Clock,
) {
    /**
     * Strategy ids this session announces to insights. Ids beginning with `__` are
     * session-internal plumbing (e.g. the bot session recorder) — they never trade,
     * so announcing them would grow a permanent ghost strategy on the dashboard.
     */
    fun strategyIds(): List<String> = strategies.map { it.first }.filterNot { it.startsWith("__") }

    /** The market-data gate judged [symbol] unhealthy for [reason]. */
    fun marketDataStale(
        symbol: String,
        reason: String,
    ) {
        if (insightsSink != null && InsightsEventFamily.LIFECYCLE in insightsEvents) {
            insightsSink.offer(
                InsightsTranslate.marketDataStale(
                    source = source.name,
                    symbol = symbol,
                    ts = clock.now(),
                    reason = reason,
                ),
            )
        }
    }

    /** An accounted fill; only an exposure-reducing fill with a net result ships as `trade.closed`. */
    fun accountedFill(
        trade: Trade,
        convertedRealized: ConvertedMoney,
        strategyId: String,
        fillState: FillState,
    ) {
        // Per-close net P&L for insights analytics. Entry commissions are real
        // cash movements, but they are not closed trades; only exposure-reducing
        // fills ship through the legacy trade.closed stream.
        val netRealized = fillState.netAccountRealized
        if (insightsSink != null &&
            fillState.reducedExposure &&
            netRealized.signum() != 0 &&
            InsightsEventFamily.TRADE in insightsEvents
        ) {
            insightsSink.offer(
                InsightsTranslate
                    .tradeClosed(
                        trade = trade,
                        netAccountRealized = netRealized,
                        strategyId = strategyId,
                        convertedRealized = convertedRealized,
                    ),
            )
        }
    }

    /** The session's brokers and feed are up; also hooks [feed]'s disconnect and reconnect. */
    fun connected(
        brokers: () -> List<Broker>,
        feed: TickFeed,
    ) {
        if (insightsSink != null && InsightsEventFamily.LIFECYCLE in insightsEvents) {
            val nowTs = clock.now()
            val brokerNames = brokers().map { it.name }.distinct()
            for (brokerName in brokerNames) {
                insightsSink.offer(
                    InsightsTranslate.brokerConnected(
                        broker = brokerName,
                        ts = nowTs,
                    ),
                )
            }
            insightsSink.offer(
                InsightsTranslate.marketDataConnected(
                    source = source.name,
                    symbols = feedSymbols,
                    ts = nowTs,
                ),
            )
            if (feed is MarketDataLifecycleFeed) {
                feed.onDisconnect { scope ->
                    insightsSink.offer(
                        InsightsTranslate.marketDataDisconnected(
                            source = scope.source ?: source.name,
                            symbols = scope.symbols ?: feedSymbols,
                            ts = clock.now(),
                            reason = "source-disconnected",
                        ),
                    )
                }
                feed.onReconnect { scope ->
                    insightsSink.offer(
                        InsightsTranslate.marketDataReconnected(
                            source = scope.source ?: source.name,
                            symbols = scope.symbols ?: feedSymbols,
                            ts = clock.now(),
                        ),
                    )
                }
            }
        }
    }

    /** The feed reader finished, for any reason. */
    fun feedEnded() {
        if (insightsSink != null && InsightsEventFamily.LIFECYCLE in insightsEvents) {
            insightsSink.offer(
                InsightsTranslate.marketDataDisconnected(
                    source = source.name,
                    symbols = feedSymbols,
                    ts = clock.now(),
                    reason = "feed-ended",
                ),
            )
        }
    }

    /** Announce every reportable strategy as started, with its deploy metadata. */
    fun strategiesStarted() {
        if (insightsSink != null && InsightsEventFamily.LIFECYCLE in insightsEvents) {
            for (strategyId in strategyIds()) {
                insightsSink.offer(
                    InsightsTranslate.strategyStarted(
                        strategyId = strategyId,
                        ts = clock.now(),
                        metadata = insightsStrategyMetadata[strategyId].orEmpty(),
                    ),
                )
            }
        }
    }

    /** Announce every reportable strategy as stopped. */
    fun strategiesStopped() {
        if (insightsSink != null && InsightsEventFamily.LIFECYCLE in insightsEvents) {
            for (strategyId in strategyIds()) {
                insightsSink.offer(
                    InsightsTranslate.strategyStopped(
                        strategyId = strategyId,
                        ts = clock.now(),
                        flatten = false,
                    ),
                )
            }
        }
    }

    /** Durable state is failing: ship the persistence [health] snapshot. */
    fun persistenceFailing(
        ownerStrategyId: String,
        health: PersistenceHealth,
    ) {
        if (insightsSink != null && InsightsEventFamily.STATE in insightsEvents) {
            insightsSink.offer(
                InsightsTranslate.statePersistence(
                    ts = clock.now(),
                    strategyId = ownerStrategyId.takeIf { it.isNotBlank() },
                    health = health,
                ),
            )
        }
    }
}
