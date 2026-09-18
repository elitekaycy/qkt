package com.qkt.cli

import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.hub.HubStoreConfig
import com.qkt.marketdata.hub.liveHubRoot
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.ReplayMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.nio.file.Path

/**
 * Shared composite-source construction for `qkt daemon` and `qkt run`.
 *
 * Routes, first match wins: the data hub (`HUB:`) when a store is configured, the cataloged macro
 * policy-rate series, then [accountRoutes] — one prefix route per trading account that supplies
 * its own prices, as built by [com.qkt.connectivity.AccountDirectory.marketDataRoutes]. How an
 * account's feed is built and shared (MT5 accounts on one gateway share one poller, for example)
 * is its connector's business, not this factory's. Anything unmatched goes to [fallbackProvider].
 *
 * The default [fallbackProvider] picks based on [source]:
 *  - `"tv"` → [TradingViewMarketSource.connect] (opens a WebSocket on construction)
 *  - `"replay"` → [ReplayMarketSource] over the CSV at `QKT_REPLAY_TICKS`. CI uses this
 *    to verify a deployed strategy processes live ticks without depending on a third-party
 *    WebSocket. Falls through to [NullMarketSource] if the env var is unset.
 *  - anything else → [NullMarketSource] (does nothing; symbols not matched by a route
 *    report `supports() == false`).
 *
 * The default existed before this knob and constructed TV unconditionally, even for
 * deployments that only used broker routes — opening a useless WebSocket and spamming
 * reconnect warnings to logs. Operators now set `source: local` in `qkt.config.yaml`
 * to suppress the TV fallback.
 *
 * Returns a closure that yields the single composite for every call site —
 * intentionally ignores its `symbols` parameter because all strategies in one daemon
 * share the same routing.
 */
object MarketSourceFactory {
    fun composite(
        accountRoutes: List<Pair<SymbolPattern, MarketSource>>,
        source: String = "tv",
        // Declared before the fallback so a trailing-lambda caller still binds to fallbackProvider.
        hub: HubStoreConfig = HubStoreConfig.NONE,
        fallbackProvider: () -> MarketSource = { defaultFallback(source) },
    ): (List<String>) -> MarketSource {
        val routes = mutableListOf<Pair<SymbolPattern, MarketSource>>()
        // The hub route exists only when a store is configured. Without one, a `HUB:` stream falls
        // through to the fallback and fails at deploy like any unknown venue prefix would.
        liveHubRoot(hub)?.let { root ->
            routes.add(
                SymbolPattern.prefix(HubMarketSource.PREFIX) to
                    HubMarketSource(root, hub.policy, staleAfterMs = hub.staleAfterMs),
            )
        }
        val policySymbols = PolicyRateSeries.entries.map { "MACRO:${it.id}" }.toSet()
        routes.add(
            SymbolPattern.exactSet(policySymbols) to
                MacroMarketSource(MacroSeriesStore(DataRoot.resolve())),
        )
        routes.addAll(accountRoutes)
        val composite = CompositeMarketSource(routes = routes, fallback = fallbackProvider())
        return { _ -> composite }
    }

    private fun defaultFallback(source: String): MarketSource =
        when (source) {
            "tv" -> TradingViewMarketSource.connect()
            "replay" -> buildReplaySource() ?: NullMarketSource
            else -> NullMarketSource
        }

    private fun buildReplaySource(): MarketSource? {
        val csv = System.getenv("QKT_REPLAY_TICKS") ?: return null
        return ReplayMarketSource(Path.of(csv))
    }
}
