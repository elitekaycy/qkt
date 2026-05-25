package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.live.bybit.BybitKlineClient
import com.qkt.marketdata.live.mt5.Mt5BarFetcher
import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch BROKER:SYMBOL --tf 5m --from 2024-01-01 --to 2024-01-31`
 * `qkt fetch BROKER:SYMBOL --tf 1h --last 30d`
 *
 * Pulls historical bars from the broker's native API and writes them to the
 * local bar store at `~/.qkt/data/bars/{BROKER}/{SYMBOL}/{TF}/{date}.csv`.
 * Idempotent — days already in the store are skipped.
 *
 * Broker dispatch:
 * - MT5 brokers (EXNESS, ICMARKETS, FTMO, PEPPERSTONE, …) — resolved via
 *   [MT5BrokerProfileLoader] from `qkt.config.yaml` + built-in defaults;
 *   uses [Mt5BarFetcher] against the profile's `gatewayUrl`.
 * - BYBIT_SPOT / BYBIT_LINEAR — uses [BybitKlineClient] against the public
 *   Bybit REST endpoint (no auth needed for kline data).
 * - BACKTEST — refused; nothing to fetch (the local store IS the backtest source).
 */
class FetchCommand(
    private val args: Args,
) {
    fun run(): Int {
        val target =
            args.positional(0) ?: run {
                System.err.println("qkt: missing BROKER:SYMBOL target")
                System.err.println("usage: qkt fetch BROKER:SYMBOL --tf <tf> --from <date> --to <date>")
                System.err.println("       qkt fetch BROKER:SYMBOL --tf <tf> --last <Nd>")
                return ExitCodes.ARG_ERROR
            }
        val parts = target.split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            System.err.println("qkt: target must be 'BROKER:SYMBOL', got '$target'")
            return ExitCodes.ARG_ERROR
        }
        val broker = parts[0]
        val symbol = parts[1]
        val tfArg =
            try {
                args.requireOption("tf")
            } catch (e: ArgError) {
                System.err.println("qkt: ${e.message}")
                return ExitCodes.ARG_ERROR
            }
        val window =
            try {
                TimeWindow.parse(tfArg)
            } catch (e: Exception) {
                System.err.println("qkt: invalid --tf '$tfArg': ${e.message}")
                return ExitCodes.ARG_ERROR
            }

        val (fromDate, toDate) =
            resolveRange(args.option("from"), args.option("to"), args.option("last"))
                ?: return ExitCodes.ARG_ERROR

        if (broker == "BACKTEST") {
            System.err.println(
                "qkt: BACKTEST is the local store; nothing to fetch from. " +
                    "Use a real broker prefix (EXNESS, ICMARKETS, BYBIT_SPOT, …) to pull historical bars.",
            )
            return ExitCodes.USER_ERROR
        }

        val fetcher = buildFetcher(broker) ?: return ExitCodes.USER_ERROR
        val store = LocalBarStore()

        val totalDays =
            java.time.temporal.ChronoUnit.DAYS
                .between(fromDate, toDate.plusDays(1))
                .toInt()
        println("qkt fetch: $broker:$symbol @ $tfArg from $fromDate to $toDate ($totalDays days)")

        var fetched = 0
        var skipped = 0
        var idx = 0
        var day = fromDate
        while (!day.isAfter(toDate)) {
            idx++
            if (store.hasDay(broker, symbol, tfArg, day)) {
                println("  [$idx/$totalDays] $day  skipped (already on disk)")
                skipped++
                day = day.plusDays(1)
                continue
            }
            val rangeStart = day.atStartOfDay(ZoneOffset.UTC).toInstant()
            val rangeEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val bars: List<Candle> =
                try {
                    fetcher.fetch(symbol, window, TimeRange(rangeStart, rangeEnd))
                } catch (e: Exception) {
                    System.err.println("  [$idx/$totalDays] $day  FAILED: ${e.message}")
                    return ExitCodes.USER_ERROR
                }
            if (bars.isEmpty()) {
                println("  [$idx/$totalDays] $day  empty (no bars returned by broker)")
            } else {
                store.writeDay(broker, symbol, tfArg, day, bars)
                store.recordDay(broker, symbol, tfArg, day)
                fetched++
                println("  [$idx/$totalDays] $day  fetched ${bars.size} bars")
            }
            day = day.plusDays(1)
        }
        println("qkt fetch: done — fetched=$fetched skipped=$skipped total=$totalDays")
        return ExitCodes.SUCCESS
    }

    /** Either (--from + --to) or (--last Nd). Returns null on a parse error after printing it. */
    private fun resolveRange(
        from: String?,
        to: String?,
        last: String?,
    ): Pair<LocalDate, LocalDate>? {
        if (last != null) {
            val days = parseLastDays(last) ?: return null
            val today = LocalDate.now(ZoneOffset.UTC)
            return today.minusDays(days.toLong()) to today.minusDays(1)
        }
        if (from == null || to == null) {
            System.err.println("qkt: need either --from + --to or --last <Nd>")
            return null
        }
        return try {
            LocalDate.parse(from) to LocalDate.parse(to)
        } catch (e: Exception) {
            System.err.println("qkt: invalid date in --from/--to: ${e.message}")
            null
        }
    }

    private fun parseLastDays(s: String): Int? {
        val m = Regex("^(\\d+)d$").matchEntire(s)
        if (m == null) {
            System.err.println("qkt: --last must be like '30d', got '$s'")
            return null
        }
        return m.groupValues[1].toInt()
    }

    private fun buildFetcher(broker: String): BarFetcher? =
        when (broker) {
            "BYBIT_SPOT" ->
                BybitFetcher(BybitKlineClient(category = "spot"))
            "BYBIT_LINEAR" ->
                BybitFetcher(BybitKlineClient(category = "linear"))
            else -> {
                // Treat as an MT5 broker — load profile and construct Mt5BarFetcher.
                val configPath =
                    args.option("config")?.let { Path.of(it) }
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
                        )
                    } catch (e: Exception) {
                        System.err.println("qkt: failed to load broker profiles: ${e.message}")
                        return null
                    }
                val profile =
                    profiles.firstOrNull { it.name == broker } ?: run {
                        System.err.println(
                            "qkt: no broker profile named '$broker' in qkt.config.yaml; " +
                                "known: ${profiles.joinToString(", ") { it.name }}",
                        )
                        return null
                    }
                Mt5Fetcher(Mt5BarFetcher(profile.gatewayUrl))
            }
        }

    /** Thin adapter so MT5 and Bybit fetchers share a common shape for [run]. */
    private interface BarFetcher {
        fun fetch(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): List<Candle>
    }

    private class Mt5Fetcher(
        private val inner: Mt5BarFetcher,
    ) : BarFetcher {
        override fun fetch(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): List<Candle> = inner.fetchRange(symbol, window, range).toList()
    }

    private class BybitFetcher(
        private val inner: BybitKlineClient,
    ) : BarFetcher {
        override fun fetch(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): List<Candle> = inner.fetchRange(symbol, window, range).toList()
    }
}
