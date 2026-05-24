package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.marketdata.live.bybit.BybitLinearMarketSource
import com.qkt.marketdata.live.bybit.BybitSpotMarketSource
import com.qkt.marketdata.live.mt5.Mt5MarketSource
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.ReplayMarketSource
import com.qkt.marketdata.source.SymbolPattern
import java.nio.file.Path

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
 *  - `"replay"` → [ReplayMarketSource] over the CSV at `QKT_REPLAY_TICKS`. CI uses this
 *    to verify a deployed strategy processes live ticks without depending on a third-party
 *    WebSocket. Falls through to [NullMarketSource] if the env var is unset.
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
        enableBybit: Boolean = defaultEnableBybit(),
        fallbackProvider: () -> MarketSource = { defaultFallback(source) },
    ): (List<String>) -> MarketSource {
        val routes = mutableListOf<Pair<SymbolPattern, MarketSource>>()
        for (p in mt5Profiles) {
            routes.add(SymbolPattern.prefix("${p.name.uppercase()}:") to Mt5MarketSource(p))
        }
        if (enableBybit) {
            routes.add(SymbolPattern.prefix("BYBIT_SPOT:") to BybitSpotMarketSource())
            routes.add(SymbolPattern.prefix("BYBIT_PERP:") to BybitLinearMarketSource())
        }
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

    /**
     * Default opt-in for Bybit routes: only construct them if the operator has set
     * `BYBIT_API_KEY` in the environment. Public Bybit market data doesn't actually need
     * auth, but the env-var presence is a reliable signal that the operator means to use
     * Bybit — pure-MT5 deployments (like the current qkt-prod) don't set it and avoid
     * two idle OkHttp clients sitting in memory.
     */
    private fun defaultEnableBybit(): Boolean = !System.getenv("BYBIT_API_KEY").isNullOrEmpty()
}
