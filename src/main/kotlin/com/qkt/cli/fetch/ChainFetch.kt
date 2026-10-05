package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionTrade
import com.qkt.derivatives.options.chain.TradeChainBuilder
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.deribit.DeribitClient
import com.qkt.marketdata.store.deribit.DeribitTradeHistory
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch DERIBIT:<ROOT> --chains`: builds the root's option chain for completed UTC days from the
 * venue's trade history and writes one snapshot file per day (`chains/<VENUE>/<ROOT>/trade/<day>.csv.gz`).
 * Each day reads trades from [Window.maxMarkAgeMs] before its start, so its file is the same however
 * the range was split; consecutive days pass that look-back along instead of fetching it twice. A
 * day is fetched once it ended [SETTLE_LAG_MS] ago (the history host trails by about a minute), is
 * refused when it traded contracts the catalog lacks (it would be written incomplete), and is
 * skipped once on disk. Needs the root under `options:` and its catalog (`--catalog`). With `--live`
 * it instead adds one snapshot of the venue's book now ([ChainLiveFetch]), a separate series.
 */
internal object ChainFetch {
    private const val DAY_MS = 86_400_000L
    private const val MIN_EVERY_MS = 60_000L

    /** How long after a day ends its trade history is taken as complete. */
    const val SETTLE_LAG_MS = 300_000L
    private val CHAIN_ONLY = listOf("live", "every", "max-mark-age")
    private val NOT_FOR_CHAINS =
        listOf("catalog", "rolls", "funding", "marks", "open-interest", "depth", "tf", "instruments", "config")

    /** Why [args] mix chain flags with another kind of fetch, or null when they do not. */
    fun misplacedFlag(args: Args): String? {
        val chains = args.flag("chains")
        val stray =
            if (chains) {
                NOT_FOR_CHAINS.firstOrNull { args.flag(it) || args.option(it) != null }
            } else {
                CHAIN_ONLY.firstOrNull { args.flag(it) || args.option(it) != null }
            }
        return stray?.let { if (chains) "--$it cannot be combined with --chains" else "--$it needs --chains" }
    }

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
        val dataRoot = DataRoot.forDataRoot(args.option("data-root"))
        if (args.flag("live")) {
            if (listOf("from", "to", "last", "every", "max-mark-age").any { args.option(it) != null }) {
                System.err.println("qkt: --chains --live takes one snapshot now; it has no range or interval")
                return ExitCodes.ARG_ERROR
            }
            return ChainLiveFetch.run(target, dataRoot)
        }
        val (from, to) =
            resolveFetchRange(args.option("from"), args.option("to"), args.option("last"))
                ?: return ExitCodes.ARG_ERROR
        val every = duration(args.option("every") ?: "1h", "every") ?: return ExitCodes.ARG_ERROR
        val maxAge = duration(args.option("max-mark-age") ?: "1d", "max-mark-age") ?: return ExitCodes.ARG_ERROR
        return run(target, dataRoot, Window(from, to, every, maxAge), utcNowMs())
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

    /** Fetches and stores [target]'s chain over [window] as of [nowMs]. */
    fun run(
        target: String,
        dataRoot: Path,
        window: Window,
        nowMs: Long,
        history: (OptionRoot, Long, Long) -> List<OptionTrade> = { root, from, to ->
            DeribitTradeHistory(DeribitClient()).trades(root, from, to)
        },
    ): Int {
        if (window.from.isAfter(window.to) || window.everyMs < MIN_EVERY_MS || DAY_MS % window.everyMs != 0L) {
            System.err.println(
                "qkt: --chains needs --from on or before --to and an --every of 1m or more that divides a day",
            )
            return ExitCodes.ARG_ERROR
        }
        val lastEnd =
            window.to
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        if (lastEnd + SETTLE_LAG_MS > nowMs) {
            System.err.println(
                "qkt: ${window.to} is not complete yet; chains are fetched ${SETTLE_LAG_MS / 60_000} minutes after a UTC day ends",
            )
            return ExitCodes.USER_ERROR
        }
        val (root, catalog) = declaredOptionChain(target, dataRoot) ?: return ExitCodes.USER_ERROR
        val builder = TradeChainBuilder(catalog, window.maxMarkAgeMs)
        val store = ChainSnapshotStore(dataRoot, QuoteSource.TRADE)
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
                if (chain.unknownContracts.isNotEmpty()) {
                    System.err.println(
                        "qkt: $day traded ${chain.unknownContracts.size} contracts missing from the catalog " +
                            "(${chain.unknownContracts.first()}…); refresh it with: qkt fetch $target --catalog",
                    )
                    return ExitCodes.USER_ERROR
                }
                try {
                    store.write(target, chain.snapshots)
                } catch (e: IOException) {
                    return failed(target, day, e)
                }
                println("  $day  ${chain.snapshots.size} snapshots from ${fresh.size} trades")
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
