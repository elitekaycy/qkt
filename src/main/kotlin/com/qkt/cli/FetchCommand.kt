package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.candles.TimeWindow
import com.qkt.cli.fetch.buildFetcher
import com.qkt.cli.fetch.resolveFetchRange
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.live.bybit.BybitKlineClient
import com.qkt.marketdata.live.mt5.Mt5BarFetcher
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.LocalBarStore
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
    /** Fetch every missing day in the range and return a process exit code. */
    fun run(): Int {
        val target =
            args.positional(0) ?: run {
                System.err.println("qkt: missing BROKER:SYMBOL target")
                System.err.println(
                    "usage: qkt fetch BROKER:SYMBOL --tf <tf> --from <date> --to <date> [--data-root <dir>]",
                )
                System.err.println("       qkt fetch BROKER:SYMBOL --tf <tf> --last <Nd> [--data-root <dir>]")
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
            resolveFetchRange(args.option("from"), args.option("to"), args.option("last"))
                ?: return ExitCodes.ARG_ERROR

        if (broker == "BACKTEST") {
            System.err.println(
                "qkt: BACKTEST is the local store; nothing to fetch from. " +
                    "Use a real broker prefix (EXNESS, ICMARKETS, BYBIT_SPOT, …) to pull historical bars.",
            )
            return ExitCodes.USER_ERROR
        }

        val fetcher = buildFetcher(broker, args.option("config")) ?: return ExitCodes.USER_ERROR
        val store = LocalBarStore(root = DataRoot.forDataRoot(args.option("data-root")))

        val totalDays =
            java.time.temporal.ChronoUnit.DAYS
                .between(fromDate, toDate.plusDays(1))
                .toInt()
        println("qkt fetch: $broker:$symbol @ $tfArg from $fromDate to $toDate ($totalDays days)")

        var fetched = 0
        var empty = 0
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
                if (fetcher.isExpectedEmpty(symbol, TimeRange(rangeStart, rangeEnd))) {
                    store.writeDay(broker, symbol, tfArg, day, bars)
                    store.recordDay(broker, symbol, tfArg, day)
                    empty++
                    println("  [$idx/$totalDays] $day  recorded empty (market closed)")
                } else {
                    println("  [$idx/$totalDays] $day  empty (open session; left uncovered for retry)")
                }
            } else {
                store.writeDay(broker, symbol, tfArg, day, bars)
                store.recordDay(broker, symbol, tfArg, day)
                fetched++
                println("  [$idx/$totalDays] $day  fetched ${bars.size} bars")
            }
            day = day.plusDays(1)
        }
        println("qkt fetch: done — fetched=$fetched empty=$empty skipped=$skipped total=$totalDays")
        return ExitCodes.SUCCESS
    }
}
