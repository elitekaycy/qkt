package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionTrade
import com.qkt.derivatives.options.chain.TradeChainBuilder
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionRoot
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.deribit.DeribitClient
import com.qkt.marketdata.store.deribit.DeribitTradeHistory
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch DERIBIT:<ROOT> --chains`: builds the root's option chain for completed UTC days from the
 * venue's trade history and writes one snapshot file per day (`chains/<VENUE>/<ROOT>/<day>.csv.gz`).
 * Each day reads trades from [Window.maxMarkAgeMs] before its start, so its file is the same however
 * the range was split; consecutive days pass that look-back along instead of fetching it twice. Days
 * already on disk are skipped. Needs the root under `options:` and its catalog (`--catalog`).
 */
internal object ChainFetch {
    private const val DAY_MS = 86_400_000L

    /** Completed UTC days [from]..[to] inclusive, a snapshot every [everyMs], marks at most [maxMarkAgeMs] old. */
    data class Window(
        val from: LocalDate,
        val to: LocalDate,
        val everyMs: Long,
        val maxMarkAgeMs: Long,
    )

    /**
     * Runs `--chains` from command-line [args]: the day range as for bars (`--from`/`--to` or
     * `--last Nd`), `--every` (default `1h`) and `--max-mark-age` (default `1d`).
     */
    fun run(
        target: String,
        args: Args,
    ): Int {
        if (!OptionCatalogFetch.handles(target.substringBefore(':'))) {
            System.err.println("qkt: --chains is available for ${DeribitClient.VENUE} option roots, not $target")
            return ExitCodes.USER_ERROR
        }
        val (from, to) =
            resolveFetchRange(args.option("from"), args.option("to"), args.option("last"))
                ?: return ExitCodes.ARG_ERROR
        val every = duration(args.option("every") ?: "1h", "every") ?: return ExitCodes.ARG_ERROR
        val maxAge = duration(args.option("max-mark-age") ?: "1d", "max-mark-age") ?: return ExitCodes.ARG_ERROR
        val dataRoot = DataRoot.forDataRoot(args.option("data-root"))
        return run(target, dataRoot, Window(from, to, every, maxAge), LocalDate.now(ZoneOffset.UTC))
    }

    private fun duration(
        spec: String,
        name: String,
    ): Long? =
        try {
            TimeWindow.parse(spec).durationMs
        } catch (e: IllegalArgumentException) {
            null.also { System.err.println("qkt: --$name must be a duration like 1h or 1d: ${e.message}") }
        } catch (e: IllegalStateException) {
            null.also { System.err.println("qkt: --$name must be a duration like 1h or 1d: ${e.message}") }
        }

    /** Fetches and stores [target]'s chain over [window]; [today] is the first day not yet over. */
    fun run(
        target: String,
        dataRoot: Path,
        window: Window,
        today: LocalDate,
        history: (OptionRoot, Long, Long) -> List<OptionTrade> = { root, from, to ->
            DeribitTradeHistory(DeribitClient()).trades(root, from, to)
        },
    ): Int {
        if (window.from.isAfter(window.to) || window.everyMs <= 0 || DAY_MS % window.everyMs != 0L) {
            System.err.println("qkt: --chains needs --from on or before --to and an --every that divides a day")
            return ExitCodes.ARG_ERROR
        }
        if (!window.to.isBefore(today)) {
            System.err.println("qkt: chains are fetched for completed UTC days; --to must be before $today")
            return ExitCodes.USER_ERROR
        }
        val root = declaredOptionRoot(target, dataRoot) ?: return ExitCodes.USER_ERROR
        val catalog =
            OptionCatalogStore(dataRoot).read(target) ?: run {
                System.err.println("qkt: no option catalog for $target; run: qkt fetch $target --catalog")
                return ExitCodes.USER_ERROR
            }
        val builder = TradeChainBuilder(catalog, window.maxMarkAgeMs)
        val store = ChainSnapshotStore(dataRoot)
        var carried = emptyList<OptionTrade>()
        var fetchedTo: Long? = null
        var day = window.from
        while (!day.isAfter(window.to)) {
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = start + DAY_MS
            if (store.hasDay(target, day)) {
                println("  $day  skipped (already on disk)")
                carried = emptyList()
                fetchedTo = null
            } else {
                val fresh =
                    try {
                        history(root, fetchedTo ?: (start - window.maxMarkAgeMs), end)
                    } catch (e: IOException) {
                        return failed(target, day, e)
                    } catch (e: IllegalStateException) {
                        return failed(target, day, e)
                    } catch (e: IllegalArgumentException) {
                        return failed(target, day, e)
                    }
                val feed = carried + fresh
                val chain = builder.build(feed, start, end, window.everyMs)
                store.write(target, chain.snapshots)
                println("  $day  ${chain.snapshots.size} snapshots from ${fresh.size} trades")
                if (chain.unknownContracts.isNotEmpty()) {
                    System.err.println(
                        "qkt: warning: $day traded ${chain.unknownContracts.size} contracts missing from the catalog " +
                            "(refresh with: qkt fetch $target --catalog)",
                    )
                }
                carried = feed.filter { it.timestampMs >= end - window.maxMarkAgeMs }
                fetchedTo = end
            }
            day = day.plusDays(1)
        }
        return ExitCodes.SUCCESS
    }

    private fun failed(
        target: String,
        day: LocalDate,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not fetch the $target chain for $day: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
