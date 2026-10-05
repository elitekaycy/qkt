package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.openAccounts
import com.qkt.marketdata.depth.BookDepthSource
import com.qkt.marketdata.depth.BookDepthStore
import com.qkt.marketdata.store.DataRoot
import java.io.IOException
import java.nio.file.Path
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:CONTRACT --depth --from D --to D`: stores the contract's order-book snapshots in
 * `<dataRoot>/depth/<VENUE>/<NAME>/<day>.csv.gz`, merged with what is there, for backtests that read
 * `<alias>.bid_depth`, `.ask_depth` or `.book_imbalance`. They come from the `brokers:` account named after the
 * venue, a `type: gateway` account whose gateway declares `depth`. No venue publishes book history, so a
 * gateway serves only what it recorded while something read it (a live strategy, every 10 seconds). The days
 * run to the end of `--to`.
 */
internal object BookDepthFetch {
    /** Fetches [target]'s order-book snapshots; returns a process exit code. */
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
                    "qkt: no depth source for '$venue' (a type: gateway account of that name in --config)",
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
        val snapshots =
            try {
                source.snapshots(target, fromMs, toMs)
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: RuntimeException) {
                return failed(target, e)
            }
        val store = BookDepthStore(DataRoot.forDataRoot(args.option("data-root")))
        val held = store.merge(target, snapshots)
        println(
            "qkt fetch: ${snapshots.size} depth snapshots for $target from $from to $to " +
                "($held stored in their days) -> ${store.directory(target)}",
        )
        return ExitCodes.SUCCESS
    }

    private fun sourceFor(
        venue: String,
        configOption: String?,
    ): BookDepthSource? {
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)?.bookDepth
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
        System.err.println("qkt: could not fetch the depth of $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
