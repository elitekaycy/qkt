package com.qkt.cli.fetch

import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.cli.Config
import com.qkt.marketdata.live.bybit.BybitKlineClient
import com.qkt.marketdata.live.mt5.Mt5BarFetcher
import java.nio.file.Path

/**
 * The fetcher for [broker]: Bybit spot/linear directly, anything else as an MT5 broker profile
 * resolved from `--config` ([configOption]) or the default config. Null after printing the reason.
 */
internal fun buildFetcher(
    broker: String,
    configOption: String?,
): BarFetcher? =
    when (broker.uppercase()) {
        "BYBIT_SPOT" ->
            BybitFetcher(BybitKlineClient(category = "spot"))
        "BYBIT_LINEAR" ->
            BybitFetcher(BybitKlineClient(category = "linear"))
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
