package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.marketdata.store.deribit.DeribitBookSnapshot
import com.qkt.marketdata.store.deribit.DeribitClient
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

/**
 * `qkt fetch DERIBIT:<ROOT> --chains --live`: one snapshot of the root's option book now, added to
 * the day's book chain file (`chains/<VENUE>/<ROOT>/book/<day>.csv.gz`). Run on a schedule it builds
 * bid/ask history the trade feed lacks; overlapping runs wait for each other's append.
 */
internal object ChainLiveFetch {
    /** Takes and stores [target]'s snapshot with [take]; returns a process exit code. */
    fun run(
        target: String,
        dataRoot: Path,
        take: (OptionRoot, OptionCatalog) -> DeribitBookSnapshot.Taken = { root, catalog ->
            DeribitBookSnapshot(DeribitClient()).take(root, catalog)
        },
    ): Int {
        val (root, catalog) = declaredOptionChain(target, dataRoot) ?: return ExitCodes.USER_ERROR
        val taken =
            try {
                take(root, catalog)
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: IllegalStateException) {
                return failed(target, e)
            } catch (e: IllegalArgumentException) {
                return failed(target, e)
            }
        val snapshot = taken.snapshot
        try {
            ChainSnapshotStore(dataRoot, QuoteSource.BOOK).append(target, snapshot)
        } catch (e: IOException) {
            return failed(target, e)
        } catch (e: IllegalStateException) {
            return failed(target, e)
        }
        println("qkt fetch: ${snapshot.quotes.size} quotes of $target at ${Instant.ofEpochMilli(snapshot.atMs)}")
        if (taken.unknownContracts.isNotEmpty()) {
            System.err.println(
                "qkt: warning: ${taken.unknownContracts.size} listed contracts are missing from the catalog " +
                    "(refresh with: qkt fetch $target --catalog)",
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun failed(
        target: String,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not snapshot the $target chain: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
