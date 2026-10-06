package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.openAccounts
import com.qkt.marketdata.openinterest.OpenInterestSource
import com.qkt.marketdata.openinterest.OpenInterestStore
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.binance.BinanceOpenInterest
import java.io.IOException
import java.nio.file.Path
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:CONTRACT --open-interest --from D --to D`: stores the contract's published open interest in
 * `<dataRoot>/open_interest/<VENUE>/<NAME>.csv`, merged with what is there, for backtests that read
 * `<alias>.open_interest`. The figures come from Binance's public API for `BINANCE_UM` (its last 30 days only),
 * else from the `brokers:` account named after the venue (a `type: gateway` account, whose gateway must
 * declare `open_interest`). The days run to the end of `--to`.
 */
internal object OpenInterestFetch {
    /** Fetches [target]'s open interest; returns a process exit code. */
    fun run(
        target: String,
        args: Args,
    ): Int {
        val (from, to) =
            resolveFetchRange(args.option("from"), args.option("to"), args.option("last"))
                ?: return ExitCodes.ARG_ERROR
        val venue = target.substringBefore(':')
        val source =
            sourceFor(venue, args.option("config")) ?: run {
                System.err.println(
                    "qkt: no open-interest source for '$venue' (BINANCE_UM, or a type: gateway account of that name in --config)",
                )
                return ExitCodes.USER_ERROR
            }
        val fromMs = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val toMs =
            to
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli() - 1
        val figures =
            try {
                source.figures(target, fromMs, toMs)
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: RuntimeException) {
                return failed(target, e)
            }
        val store = OpenInterestStore(DataRoot.forDataRoot(args.option("data-root")))
        val held = store.merge(target, figures)
        val path = store.path(target)
        println(
            "qkt fetch: ${figures.size} open-interest figures for $target from $from to $to ($held stored) -> $path",
        )
        return ExitCodes.SUCCESS
    }

    private fun sourceFor(
        venue: String,
        configOption: String?,
    ): OpenInterestSource? {
        if (venue == BinanceUmFetcher.VENUE) return BinanceOpenInterest()
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)?.openInterest
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
        System.err.println("qkt: could not fetch the open interest of $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
