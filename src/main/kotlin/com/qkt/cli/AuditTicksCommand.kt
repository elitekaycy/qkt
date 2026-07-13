package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5Client
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

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
            )
        }

        val tvLatest = AtomicReference<Tick?>(null)
        val tvSource = TradingViewMarketSource.connect()
        val tvFeed = tvSource.liveTicks(listOf(symbol))

        val tvThread =
            Thread({
                while (!Thread.currentThread().isInterrupted) {
                    val t = tvFeed.next() ?: break
                    if (t.symbol == symbol) tvLatest.set(t)
                }
            }, "qkt-audit-tv-feed")
        tvThread.isDaemon = true
        tvThread.start()

        val samples = mutableListOf<Sample>()
        val deadline = System.currentTimeMillis() + duration * 1000L
        try {
            while (System.currentTimeMillis() < deadline) {
                val tvTick = tvLatest.get()
                val mt5Tick = mt5Client.getTick(brokerSymbol)
                if (tvTick != null && mt5Tick != null) {
                    val tvMid = tvTick.price
                    val mt5Mid = mt5Tick.bid.add(mt5Tick.ask).divide(BigDecimal("2"), MC)
                    samples.add(Sample(absDiff = tvMid.subtract(mt5Mid).abs()))
                }
                Thread.sleep(pollMs)
            }
        } finally {
            runCatching { tvFeed.close() }
            tvThread.interrupt()
        }

        if (samples.isEmpty()) {
            println("no samples captured (TV feed may not have produced ticks for $symbol)")
            return ExitCodes.USER_ERROR
        }

        val sortedDiffs = samples.map { it.absDiff }.sorted()
        val mean =
            sortedDiffs
                .reduce { a, b -> a.add(b) }
                .divide(BigDecimal(sortedDiffs.size), MC)
        val median = sortedDiffs[sortedDiffs.size / 2]
        val p95 = sortedDiffs[(sortedDiffs.size * 95 / 100).coerceAtMost(sortedDiffs.size - 1)]
        val max = sortedDiffs.last()

        val json =
            """{"symbol":"$symbol","samples":${samples.size},""" +
                """"mean_abs_diff":"${mean.toPlainString()}",""" +
                """"median_abs_diff":"${median.toPlainString()}",""" +
                """"p95_abs_diff":"${p95.toPlainString()}",""" +
                """"max_abs_diff":"${max.toPlainString()}"}"""

        if (args.flag("json")) {
            println(json)
        } else {
            println("samples:        ${samples.size}")
            println("mean abs diff:  ${mean.toPlainString()}")
            println("median abs diff:${median.toPlainString()}")
            println("p95 abs diff:   ${p95.toPlainString()}")
            println("max abs diff:   ${max.toPlainString()}")
        }

        // --out <path> persists the JSON to disk regardless of the stdout format flag.
        // Operators recording audits append each run's JSON to the results table in
        // docs/operations/tick-feed-audit.md; persisting to a stable path makes that
        // a one-command workflow instead of "remember to redirect stdout."
        persist(json)

        return ExitCodes.SUCCESS
    }

    private fun runMt5HistoryAudit(
        client: MT5Client,
        brokerSymbol: String,
        qktSymbol: String,
        profileName: String,
        durationSeconds: Long,
        pollMs: Long,
        settleMs: Long,
    ): Int {
        val startedAtMs = System.currentTimeMillis()
        val deadline = startedAtMs + durationSeconds * 1_000L
        val observations = mutableListOf<ObservedMt5Tick>()
        while (System.currentTimeMillis() < deadline) {
            client.getTick(brokerSymbol)?.let { tick ->
                observations += ObservedMt5Tick(System.currentTimeMillis(), tick)
            }
            Thread.sleep(pollMs)
        }
        val endedAtMs = System.currentTimeMillis()
        Thread.sleep(settleMs)
        val history =
            client.getTicksRange(brokerSymbol, startedAtMs, endedAtMs)
                ?: run {
                    System.err.println("qkt audit-ticks: MT5 raw tick history is unavailable")
                    return ExitCodes.USER_ERROR
                }
        val result =
            runCatching {
                Mt5FeedAudit.compare(qktSymbol, startedAtMs, endedAtMs, observations, history)
            }.getOrElse { error ->
                System.err.println("qkt audit-ticks: ${error.message}")
                return ExitCodes.USER_ERROR
            }
        val json =
            Mt5FeedAudit.artifactJson(
                result = result,
                venueSymbol = brokerSymbol,
                profileName = profileName,
                durationSeconds = durationSeconds,
                pollMs = pollMs,
                settleMs = settleMs,
            )
        if (args.flag("json")) {
            println(json)
        } else {
            println("poll samples:             ${result.pollSamples}")
            println("unique in-window ticks:   ${result.uniqueLiveTicks}")
            println("history ticks:            ${result.historyTicks}")
            println("exact timestamp matches:  ${result.exactTimestampMatches}")
            println("exact bid/ask matches:    ${result.exactPriceMatches}")
            println("timestamp price mismatch: ${result.timestampPriceMismatches}")
            println("missing from history:     ${result.missingFromHistory}")
            println("invalid live quotes:      ${result.invalidLiveQuotes}")
            println("quote age p95 ms:         ${result.quoteAgeMs.p95}")
            println("result:                   ${if (result.passed) "PASS" else "FAIL"}")
        }
        persist(json)
        return if (result.passed) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }

    private fun persist(json: String) {
        args.option("out")?.let { outPath ->
            val path = Path.of(outPath)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, json + "\n")
            System.err.println("qkt audit-ticks: wrote $outPath")
        }
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

    private data class Sample(
        val absDiff: BigDecimal,
    )

    companion object {
        private val MC = MathContext(8, RoundingMode.HALF_EVEN)
        private const val DEFAULT_MT5_HISTORY_SETTLE_MS = 15_000L
    }
}
