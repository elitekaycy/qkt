package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.marketdata.live.bybit.BybitLinearMarketSource
import com.qkt.marketdata.live.bybit.BybitSpotMarketSource
import com.qkt.marketdata.live.mt5.Mt5MarketSource
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.SymbolPattern

/**
 * Shared composite-source construction for `qkt daemon` and `qkt run`.
 *
 * Builds one [Mt5MarketSource] per loaded MT5 broker profile (prefix derived from
 * `profile.name.uppercase()+:`), registers [BybitSpotMarketSource] + [BybitLinearMarketSource]
 * unconditionally (WS open is lazy on first `liveTicks`), and uses [fallbackProvider]
 * as the catch-all.
 *
 * The default [fallbackProvider] picks based on [source]:
 *  - `"tv"` → [TradingViewMarketSource.connect] (opens a WebSocket on construction)
 *  - anything else → [NullMarketSource] (does nothing; symbols not matched by a route
 *    report `supports() == false`).
 *
 * The default existed before this knob and constructed TV unconditionally, even for
 * deployments that only used MT5 routes — opening a useless WebSocket and spamming
 * reconnect warnings to logs. Operators now set `source: local` in `qkt.config.yaml`
 * to suppress the TV fallback.
 *
 * Returns a closure that yields the single composite for every call site —
 * intentionally ignores its `symbols` parameter because all strategies in one daemon
 * share the same routing.
 */
object MarketSourceFactory {
    fun composite(
        mt5Profiles: List<MT5BrokerProfile>,
        source: String = "tv",
        fallbackProvider: () -> MarketSource = { defaultFallback(source) },
    ): (List<String>) -> MarketSource {
        val routes = mutableListOf<Pair<SymbolPattern, MarketSource>>()
        for (p in mt5Profiles) {
            routes.add(SymbolPattern.prefix("${p.name.uppercase()}:") to Mt5MarketSource(p))
        }
        routes.add(SymbolPattern.prefix("BYBIT_SPOT:") to BybitSpotMarketSource())
        routes.add(SymbolPattern.prefix("BYBIT_PERP:") to BybitLinearMarketSource())
        val composite = CompositeMarketSource(routes = routes, fallback = fallbackProvider())
        return { _ -> composite }
    }

    private fun defaultFallback(source: String): MarketSource =
        if (source == "tv") TradingViewMarketSource.connect() else NullMarketSource
}
