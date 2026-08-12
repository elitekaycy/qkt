package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.marketdata.live.bybit.BybitLinearMarketSource
import com.qkt.marketdata.live.bybit.BybitSpotMarketSource
import com.qkt.marketdata.live.mt5.Mt5MarketSource
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.PrefixRemapMarketSource
import com.qkt.marketdata.source.ReplayMarketSource
import com.qkt.marketdata.source.SharedLiveMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.nio.file.Path

/**
 * Everything [Mt5MarketSource] actually reads from an [MT5BrokerProfile]. Profiles sharing
 * this identity poll the same gateway with the same credentials, symbol translation, poll
 * cadence, session calendars, and history retries — one live poller can serve all of them.
 * `name` and `magic` are deliberately absent: they tag orders, not market data.
 */
internal data class Mt5MarketDataIdentity(
    val gatewayUrl: String,
    val apiKey: String?,
    val serverTimeZone: MT5ServerTimeZone,
    val symbolPolicy: SymbolPolicy,
    val tickPollIntervalMs: Long,
    val symbolCalendars: SymbolCalendars,
    val retryAttempts: Int,
) {
    companion object {
        fun of(profile: MT5BrokerProfile): Mt5MarketDataIdentity =
            Mt5MarketDataIdentity(
                gatewayUrl = profile.gatewayUrl,
                apiKey = profile.apiKey,
                serverTimeZone = profile.serverTimeZone,
                symbolPolicy = profile.symbolPolicy,
                tickPollIntervalMs = profile.tickPollIntervalMs,
                symbolCalendars = profile.symbolCalendars,
                retryAttempts = profile.retryAttempts,
            )
    }
}

/**
 * Shared composite-source construction for `qkt daemon` and `qkt run`.
 *
 * Builds one [Mt5MarketSource] per group of MT5 broker profiles that share a
 * [Mt5MarketDataIdentity] (profiles typically differ only in `name`/`magic`, e.g. one
 * profile per strategy against the same gateway account), wrapped in a single
 * [SharedLiveMarketSource]. The canonical (first) profile of a group registers its
 * `<NAME>:` prefix route directly on the shared source; every other profile in the group
 * routes its prefix through a [PrefixRemapMarketSource] onto the same shared source, so the
 * gateway sees one tick poller per identity instead of one per profile. A singleton group
 * behaves exactly as before — no remapping wrapper. Profiles differing in any identity field
 * keep their own pollers.
 *
 * Routes symbols by prefix `<NAME>:` (`profile.name.uppercase()+:`), registers
 * [BybitSpotMarketSource] + [BybitLinearMarketSource] when enabled (WS open is lazy on first
 * `liveTicks`), and uses [fallbackProvider] as the catch-all.
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
        val policySymbols = PolicyRateSeries.entries.map { "MACRO:${it.id}" }.toSet()
        routes.add(
            SymbolPattern.exactSet(policySymbols) to
                MacroMarketSource(MacroSeriesStore(DataRoot.resolve())),
        )
        for (group in groupByMarketDataIdentity(mt5Profiles)) {
            val canonical = group.first()
            val canonicalPrefix = "${canonical.name.uppercase()}:"
            val shared = SharedLiveMarketSource(Mt5MarketSource(canonical))
            routes.add(SymbolPattern.prefix(canonicalPrefix) to shared)
            for (profile in group.drop(1)) {
                val localPrefix = "${profile.name.uppercase()}:"
                routes.add(
                    SymbolPattern.prefix(localPrefix) to
                        PrefixRemapMarketSource(
                            delegate = shared,
                            delegatePrefix = canonicalPrefix,
                            localPrefix = localPrefix,
                        ),
                )
            }
        }
        if (enableBybit) {
            routes.add(SymbolPattern.prefix("BYBIT_SPOT:") to BybitSpotMarketSource())
            routes.add(SymbolPattern.prefix("BYBIT_LINEAR:") to BybitLinearMarketSource())
        }
        val composite = CompositeMarketSource(routes = routes, fallback = fallbackProvider())
        return { _ -> composite }
    }

    /**
     * Group profiles by [Mt5MarketDataIdentity], preserving declaration order: the first
     * profile of a group is its canonical poller owner. Exposed for tests.
     */
    internal fun groupByMarketDataIdentity(profiles: List<MT5BrokerProfile>): List<List<MT5BrokerProfile>> =
        profiles.groupBy(Mt5MarketDataIdentity::of).values.toList()

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
