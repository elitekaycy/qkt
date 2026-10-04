package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.openAccounts
import com.qkt.instrument.FundingRateSource
import com.qkt.instrument.FundingRateStore
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.binance.BinanceFundingRates
import java.io.IOException
import java.nio.file.Path
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:PERPETUAL --funding --from D --to D`: stores the perpetual's published funding rates in
 * `<dataRoot>/funding/<VENUE>/<NAME>.csv`, merged with what is there, for backtests to charge. The rates come
 * from Binance's public API for `BINANCE_UM`, else from the `brokers:` account named after the venue (a
 * `type: gateway` account, whose gateway must declare `funding_rates`). The days run to the end of `--to`.
 */
internal object FundingFetch {
    /** Fetches [target]'s funding rates; returns a process exit code. */
    fun run(
        target: String,
        args: Args,
    ): Int {
        val (from, to) = resolveFetchRange(args.option("from"), args.option("to"), args.option("last")) ?: return ExitCodes.ARG_ERROR
        val venue = target.substringBefore(':')
        val source =
            sourceFor(venue, args.option("config")) ?: run {
                System.err.println("qkt: no funding-rate source for '$venue' (BINANCE_UM, or a type: gateway account of that name in --config)")
                return ExitCodes.USER_ERROR
            }
        val fromMs = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        val rates =
            try {
                source.rates(target, fromMs, toMs)
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: RuntimeException) {
                return failed(target, e)
            }
        val store = FundingRateStore(DataRoot.forDataRoot(args.option("data-root")))
        val held = store.merge(target, rates)
        println("qkt fetch: ${rates.size} funding rates for $target from $from to $to ($held stored) -> ${store.path(target)}")
        return ExitCodes.SUCCESS
    }

    private fun sourceFor(
        venue: String,
        configOption: String?,
    ): FundingRateSource? {
        if (venue == BinanceUmFetcher.VENUE) return BinanceFundingRates()
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)?.fundingRates
        } catch (e: IllegalArgumentException) {
            null.also { System.err.println("qkt: failed to open broker '$venue': ${e.message}") }
        } catch (e: IllegalStateException) {
            null.also { System.err.println("qkt: failed to open broker '$venue': ${e.message}") }
        }
    }

    private fun failed(
        target: String,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not fetch the funding rates of $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
