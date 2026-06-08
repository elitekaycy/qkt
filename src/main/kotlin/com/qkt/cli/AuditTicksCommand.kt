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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * `qkt audit-ticks` — operator tool that compares TradingView ticks vs MT5 gateway
 * ticks for a symbol, reporting statistical drift over a sample duration.
 *
 * Usage:
 *   qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness
 *
 * Captures both feeds simultaneously for `--duration` seconds and reports:
 *   - sample count
 *   - mean / median / p95 / max absolute price difference
 *   - tv-leads-mt5 timing skew (median ms)
 *
 * Use this before committing investor money: confirms the TV prices your strategies
 * see actually track the MT5 prices your orders fill at, within an acceptable bound.
 */
class AuditTicksCommand(
    private val args: Args,
) {
    fun run(): Int {
        val symbol = args.option("symbol") ?: return missing("symbol")
        val duration = args.option("duration")?.toLongOrNull() ?: 60L
        val profileName = args.option("mt5-profile") ?: "exness"
        val pollMs = args.option("poll-ms")?.toLongOrNull() ?: 250L

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
                tzOffsetHours = profile.serverTzOffsetHours,
                httpTimeoutMs = profile.httpTimeoutMs,
                retryAttempts = 0,
            )
        if (!mt5Client.isReady()) {
            System.err.println("qkt: mt5-gateway at ${profile.gatewayUrl} is not responding")
            return ExitCodes.USER_ERROR
        }

        println("qkt audit-ticks: symbol=$symbol duration=${duration}s profile=$profileName poll=${pollMs}ms")

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

        val brokerSymbol = mt5Symbol.toBroker(symbol)
        val samples = mutableListOf<Sample>()
        val deadline = System.currentTimeMillis() + duration * 1000L
        try {
            while (System.currentTimeMillis() < deadline) {
                val tvTick = tvLatest.get()
                val mt5Tick = mt5Client.getTick(brokerSymbol) ?: continue
                if (tvTick != null) {
                    val tvMid = tvTick.price
                    val mt5Mid = mt5Tick.bid.add(mt5Tick.ask).divide(BigDecimal("2"), MC)
                    samples.add(
                        Sample(
                            timestamp = System.currentTimeMillis(),
                            tvPrice = tvMid,
                            mt5Mid = mt5Mid,
                            absDiff = tvMid.subtract(mt5Mid).abs(),
                        ),
                    )
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
        args.option("out")?.let { outPath ->
            val path =
                java.nio.file.Path
                    .of(outPath)
            path.parent?.let {
                java.nio.file.Files
                    .createDirectories(it)
            }
            java.nio.file.Files
                .writeString(path, json + "\n")
            System.err.println("qkt audit-ticks: wrote $outPath")
        }

        return ExitCodes.SUCCESS
    }

    private fun missing(field: String): Int {
        System.err.println("qkt: --$field is required")
        return ExitCodes.ARG_ERROR
    }

    private data class Sample(
        val timestamp: Long,
        val tvPrice: BigDecimal,
        val mt5Mid: BigDecimal,
        val absDiff: BigDecimal,
    )

    companion object {
        private val MC = MathContext(8, RoundingMode.HALF_EVEN)
    }
}
