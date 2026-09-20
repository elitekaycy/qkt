package com.qkt.cli

import com.qkt.cli.audit.runMt5HistoryAudit
import com.qkt.cli.audit.runTradingViewDriftAudit
import com.qkt.connector.mt5.MT5BrokerProfileLoader
import com.qkt.connector.mt5.MT5Client
import com.qkt.connector.mt5.MT5DefaultProfiles
import com.qkt.connector.mt5.MT5Symbol
import java.nio.file.Path

/**
 * `qkt audit-ticks` — operator tool that compares TradingView ticks with the MT5 gateway,
 * or reconciles polled MT5 quotes against raw MT5 history for the same UTC window.
 *
 * Usage:
 *   qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness
 *   qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness --reference mt5-history
 *
 * Captures for `--duration` seconds and reports cross-source price drift or exact
 * live/history timestamp and bid/ask reconciliation, depending on the reference mode.
 *
 * Use this before committing investor money: the TradingView mode quantifies cross-source
 * drift, while the MT5-history mode proves that live quotes survive into raw venue history
 * without timestamp or price mutation.
 */
class AuditTicksCommand(
    private val args: Args,
) {
    /** Run the selected audit and return a process exit code. */
    fun run(): Int {
        val symbol = args.option("symbol") ?: return missing("symbol")
        val duration = args.option("duration")?.toLongOrNull() ?: 60L
        val profileName = args.option("mt5-profile") ?: "exness"
        val pollMs = args.option("poll-ms")?.toLongOrNull() ?: 250L
        val reference = args.option("reference") ?: "tradingview"
        val settleMs = args.option("settle-ms")?.toLongOrNull() ?: DEFAULT_MT5_HISTORY_SETTLE_MS
        if (duration <= 0L) return invalid("duration", "must be positive")
        if (pollMs <= 0L) return invalid("poll-ms", "must be positive")
        if (settleMs < 0L) return invalid("settle-ms", "must be non-negative")
        if (reference !in setOf("tradingview", "mt5-history")) {
            return invalid("reference", "expected tradingview or mt5-history")
        }

        val configPath =
            args.option("config")?.let { Path.of(it) }
                ?: Config.locate()
                ?: run {
                    System.err.println(
                        "qkt: no qkt.config.yaml found in any of " +
                            Config
                                .defaultSearchPaths()
                                .joinToString(", "),
                    )
                    System.err.println("qkt: pass --config <path> or place the file at one of the above locations")
                    return ExitCodes.USER_ERROR
                }
        val config = Config.load(configPath)
        val profile =
            try {
                MT5BrokerProfileLoader().load(
                    raw = config.brokers,
                    defaults = MT5DefaultProfiles.all,
                    env = System.getenv(),
                    calendars = config.brokerCalendars,
                    aliases = config.brokerAliases,
                    capabilityRestrictions = config.brokerCapabilityRestrictions,
                    instrumentOverrides = config.brokerInstrumentOverrides,
                )
            } catch (e: Exception) {
                System.err.println("qkt: brokers load failed: ${e.message}")
                return ExitCodes.USER_ERROR
            }.firstOrNull { it.name == profileName }
                ?: run {
                    System.err.println("qkt: profile '$profileName' not found")
                    return ExitCodes.USER_ERROR
                }

        val mt5Symbol = MT5Symbol(profile.symbolPolicy)
        val mt5Client =
            MT5Client(
                gatewayUrl = profile.gatewayUrl,
                serverTimeZone = profile.serverTimeZone,
                httpTimeoutMs = profile.httpTimeoutMs,
                retryAttempts = 0,
                apiKey = profile.apiKey,
            )
        if (!mt5Client.isReady()) {
            System.err.println("qkt: mt5-gateway at ${profile.gatewayUrl} is not responding")
            return ExitCodes.USER_ERROR
        }

        println(
            "qkt audit-ticks: symbol=$symbol duration=${duration}s profile=$profileName " +
                "reference=$reference poll=${pollMs}ms",
        )

        val mt5InputSymbol = Mt5FeedAudit.inputSymbol(symbol, args.option("mt5-symbol"))
        val brokerSymbol = mt5Symbol.toBroker(mt5InputSymbol)
        if (reference == "mt5-history") {
            return runMt5HistoryAudit(
                client = mt5Client,
                brokerSymbol = brokerSymbol,
                qktSymbol = symbol,
                profileName = profileName,
                durationSeconds = duration,
                pollMs = pollMs,
                settleMs = settleMs,
                jsonOutput = args.flag("json"),
                outPath = args.option("out"),
            )
        }
        return runTradingViewDriftAudit(
            client = mt5Client,
            symbol = symbol,
            brokerSymbol = brokerSymbol,
            durationSeconds = duration,
            pollMs = pollMs,
            jsonOutput = args.flag("json"),
            outPath = args.option("out"),
        )
    }

    private fun missing(field: String): Int {
        System.err.println("qkt: --$field is required")
        return ExitCodes.ARG_ERROR
    }

    private fun invalid(
        field: String,
        reason: String,
    ): Int {
        System.err.println("qkt: --$field $reason")
        return ExitCodes.ARG_ERROR
    }

    companion object {
        private const val DEFAULT_MT5_HISTORY_SETTLE_MS = 15_000L
    }
}
