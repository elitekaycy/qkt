package com.qkt.app

import com.qkt.marketdata.FeedFault
import com.qkt.marketdata.MarketDataGateConfig
import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Strategy
import java.util.concurrent.ConcurrentHashMap

/**
 * What a live session holds on one symbol: an open position and how many orders are still working.
 * Engine-held stops and time exits are among those orders, and none of them can trigger without ticks.
 */
internal data class SymbolExposure(
    val positionOpen: Boolean,
    val workingOrders: Int,
) {
    /** Whether a frozen quote leaves anything of this session's unprotected. */
    val any: Boolean get() = positionOpen || workingOrders > 0

    /** e.g. "position open and 2 working order(s) on EXNESS:XAUUSD". */
    fun describe(symbol: String): String =
        when {
            positionOpen && workingOrders > 0 -> "position open and $workingOrders working order(s) on $symbol"
            positionOpen -> "position open on $symbol"
            else -> "$workingOrders working order(s) on $symbol"
        }

    companion object {
        val NONE = SymbolExposure(positionOpen = false, workingOrders = 0)
    }
}

/**
 * What a live session does when the market-data gate judges a symbol unhealthy. Insights always hears
 * of it, so the collector can measure the episode. The operator is paged once per episode, and only
 * when it matters:
 * - at once for a clock skew or an outlier run;
 * - at once for a stale quote on a symbol this session holds a position or working orders in, e.g.
 *   "market data unhealthy for EXNESS:XAUUSD: quote age 61000ms exceeds 60000ms threshold; position open
 *   on EXNESS:XAUUSD: engine-held stops and exits cannot trigger without ticks";
 * - for a stale quote with nothing open, only once the venue has stayed silent in session for
 *   [MarketDataGateConfig.quietAlertAfterMs]. A thin market's quiet minutes are not worth a page (measured on prop copper,
 *   where the broker's own tick history goes quiet for up to 8 minutes), but a feed that stays dead
 *   silently stops the symbol's strategies from trading.
 *
 * A [MarketDataGateConfig.quietAlertAfterMs] of 0 pages every stale quote at once. A recovery goes to insights only.
 */
internal class MarketDataHealthAlerts(
    private val strategies: List<Pair<String, Strategy>>,
    private val sessionNotifier: SessionNotifier,
    private val insights: InsightsLifecycle,
    config: MarketDataGateConfig = MarketDataGateConfig.DEFAULT,
) {
    private val quietAlertAfterMs = config.quietAlertAfterMs

    /** What this session holds on a symbol; bound once the pipeline exists. */
    var exposureFor: (symbol: String) -> SymbolExposure = { SymbolExposure.NONE }

    /** Stale episodes still waiting for exposure or the quiet threshold before they page. */
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Binds [exposureFor] to [pipeline]'s positions and live orders. */
    fun bind(pipeline: TradingPipeline) {
        exposureFor = { symbol ->
            SymbolExposure(
                positionOpen =
                    pipeline.positions
                        .positionFor(symbol)
                        ?.quantity
                        ?.let { it.signum() != 0 } ?: false,
                workingOrders = pipeline.orderManager.activeOrders().count { it.request.symbol == symbol },
            )
        }
    }

    /** The gate's unhealthy callback for [symbol]. */
    fun onUnhealthy(
        symbol: String,
        reason: String,
        fault: FeedFault,
    ) {
        insights.marketDataStale(symbol, reason, fault.wireName)
        if (fault != FeedFault.STALE) {
            page("market data unhealthy for $symbol: $reason")
            return
        }
        val exposure = exposureFor(symbol)
        when {
            exposure.any -> page(exposedMessage(symbol, reason, exposure))
            quietAlertAfterMs == 0L ->
                page(
                    "market data unhealthy for $symbol: $reason; nothing open on $symbol in this session",
                )
            else -> held.add(symbol)
        }
    }

    /**
     * The gate's report that [symbol]'s stale episode is still open after [unhealthyForMs], made on the
     * engine heartbeat while the venue is in session and outside a scheduled break. A held episode pages
     * once exposure appears or the quiet threshold is reached.
     */
    fun onStillStale(
        symbol: String,
        unhealthyForMs: Long,
    ) {
        if (symbol !in held) return
        val exposure = exposureFor(symbol)
        val quietFor = duration(unhealthyForMs)
        when {
            exposure.any -> page(exposedMessage(symbol, "no quote for $quietFor", exposure))
            unhealthyForMs >= quietAlertAfterMs ->
                page(
                    "market data for $symbol: no quote for $quietFor while its venue is in session; nothing open " +
                        "on $symbol in this session; new orders on $symbol stay suppressed until quotes return",
                )
            else -> return
        }
        held.remove(symbol)
    }

    /** The gate's recovery callback: [symbol] is healthy again after [unhealthyForMs]; insights only. */
    fun onRecovered(
        symbol: String,
        reason: String,
        unhealthyForMs: Long,
    ) {
        held.remove(symbol)
        insights.marketDataRecovered(symbol, reason, unhealthyForMs)
    }

    private fun exposedMessage(
        symbol: String,
        reason: String,
        exposure: SymbolExposure,
    ): String =
        "market data unhealthy for $symbol: $reason; ${exposure.describe(symbol)}: " +
            "engine-held stops and exits cannot trigger without ticks"

    private fun page(message: String) {
        if (!sessionNotifier.enabled(NotifyEventKind.STRATEGY_ERROR)) return
        for ((strategyId, _) in strategies) {
            sessionNotifier.strategyError(strategyId, "MarketDataUnhealthy") { message }
        }
    }

    private fun duration(ms: Long): String = if (ms >= MS_PER_MINUTE) "${ms / MS_PER_MINUTE}m" else "${ms / 1_000L}s"

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
