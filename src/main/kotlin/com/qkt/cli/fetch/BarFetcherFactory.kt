package com.qkt.cli.fetch

import com.qkt.cli.Config
import com.qkt.cli.openAccounts
import com.qkt.connector.bybit.marketdata.BybitKlineClient
import com.qkt.connector.mt5.MT5BrokerProfileLoader
import com.qkt.connector.mt5.MT5DefaultProfiles
import com.qkt.connector.mt5.MT5Symbol
import com.qkt.connector.mt5.marketdata.Mt5BarFetcher
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.store.binance.BinanceVisionClient
import java.nio.file.Path

/**
 * The fetcher for [broker]: Bybit spot/linear and Binance USDⓈ-M directly; anything else is a `brokers:` entry of
 * `--config` ([configOption]) or the default config: an MT5 broker profile, or an account of another connector whose
 * own feed serves bars (a `type: gateway` venue). Null after printing the reason.
 */
internal fun buildFetcher(
    broker: String,
    configOption: String?,
): BarFetcher? {
    return when (broker.uppercase()) {
        "BYBIT_SPOT" ->
            BybitFetcher(BybitKlineClient(category = "spot"))
        "BYBIT_LINEAR" ->
            BybitFetcher(BybitKlineClient(category = "linear"))
        BinanceUmFetcher.VENUE -> BinanceUmFetcher(BinanceVisionClient())
        else -> {
            // Treat as an MT5 broker — load profile and construct Mt5BarFetcher.
            val configPath =
                configOption?.let { Path.of(it) }
                    ?: Config.locate() ?: run {
                    System.err.println(
                        "qkt: no qkt.config.yaml found (need it to resolve MT5 broker '$broker'); " +
                            "pass --config <path> or place the file under " +
                            Config.defaultSearchPaths().joinToString(", "),
                    )
                    return null
                }
            val cfg = Config.load(configPath)
            val type =
                cfg.brokers.entries
                    .firstOrNull { it.key.equals(broker, ignoreCase = true) }
                    ?.value
                    ?.get("type")
            if (type != null && type != "mt5") return accountFetcher(cfg, broker)
            val profiles =
                try {
                    MT5BrokerProfileLoader().load(
                        raw = cfg.brokers,
                        defaults = MT5DefaultProfiles.all,
                        env = System.getenv(),
                        calendars = cfg.brokerCalendars,
                        aliases = cfg.brokerAliases,
                        capabilityRestrictions = cfg.brokerCapabilityRestrictions,
                        instrumentOverrides = cfg.brokerInstrumentOverrides,
                    )
                } catch (e: Exception) {
                    System.err.println("qkt: failed to load broker profiles: ${e.message}")
                    return null
                }
            val profile =
                profiles.firstOrNull { it.name.equals(broker, ignoreCase = true) } ?: run {
                    System.err.println(
                        "qkt: no broker profile named '$broker' in qkt.config.yaml; " +
                            "known: ${profiles.joinToString(", ") { it.name }}",
                    )
                    return null
                }
            Mt5Fetcher(
                Mt5BarFetcher(
                    profile.gatewayUrl,
                    serverTimeZone = profile.serverTimeZone,
                    normalizeBidBarsToMid = true,
                    apiKey = profile.apiKey,
                ),
                MT5Symbol(profile.symbolPolicy),
                profile.symbolCalendars,
            )
        }
    }
}

/** Bars from the feed of the account named [broker], when its connector serves any. */
private fun accountFetcher(
    cfg: Config,
    broker: String,
): BarFetcher? {
    val account =
        try {
            cfg.openAccounts().byName(broker)
        } catch (e: Exception) {
            System.err.println("qkt: failed to open broker '$broker': ${e.message}")
            return null
        }
    val source = account?.marketData?.takeIf { MarketSourceCapability.BARS in it.capabilities }
    if (account == null || source == null) {
        System.err.println("qkt: broker '$broker' serves no historical bars")
        return null
    }
    return SourceFetcher(source, account.config.symbolPrefix)
}
