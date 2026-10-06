package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.openAccounts
import com.qkt.common.Clock
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.PrintHistorySource
import com.qkt.marketdata.flow.TapeStore
import com.qkt.marketdata.store.DataRoot
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:CONTRACT --tape --from D --to D` (or `--liquidations`): stores the contract's public trade tape,
 * every print with its aggressor side (or the prints that liquidated a position), as one file per UTC day under
 * `<dataRoot>/tape/<VENUE>/<NAME>/` (or `liquidations/`), for backtests to replay. A day is written whole, replacing
 * what was stored, and only once it is over by [Clock]; a day nothing printed in is stored empty. The prints come
 * from the `brokers:` account named after the venue (a `type: gateway` account, whose gateway must declare `trades`
 * or `liquidations`).
 */
internal object TapeFetch {
    /** Fetches [target]'s [kind]; returns a process exit code. */
    fun run(
        target: String,
        kind: FlowKind,
        args: Args,
        clock: Clock,
    ): Int {
        val (from, to) =
            resolveFetchRange(args.option("from"), args.option("to"), args.option("last")) ?: return ExitCodes.ARG_ERROR
        val venue = target.substringBefore(':')
        val source =
            sourceFor(venue, args.option("config")) ?: run {
                System.err.println(
                    "qkt: no ${kind.capability} for '$venue' (a type: gateway account of that name in --config)",
                )
                return ExitCodes.USER_ERROR
            }
        val store = TapeStore(DataRoot.forDataRoot(args.option("data-root")))
        val over = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) && endMs(it) <= clock.now() }
        var stored = 0
        var prints = 0
        for (day in over) {
            val read =
                try {
                    source.prints(target, kind, endMs(day) - DAY_MS, endMs(day))
                } catch (e: IOException) {
                    return failed(target, kind, day, e)
                } catch (e: RuntimeException) {
                    return failed(target, kind, day, e)
                }
            store.write(target, kind, day, read)
            stored++
            prints += read.size
        }
        val skipped =
            if (stored <
                from.datesUntil(to.plusDays(1)).count()
            ) {
                " (days not yet over are not stored)"
            } else {
                ""
            }
        println(
            "qkt fetch: $prints ${kind.capability} of $target over $stored day(s)$skipped -> ${store.dir(
                target,
                kind,
            )}",
        )
        return ExitCodes.SUCCESS
    }

    private fun sourceFor(
        venue: String,
        configOption: String?,
    ): PrintHistorySource? {
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)?.printHistory
        } catch (e: IllegalArgumentException) {
            null.also { System.err.println("qkt: failed to open broker '$venue': ${e.message}") }
        } catch (e: IllegalStateException) {
            null.also { System.err.println("qkt: failed to open broker '$venue': ${e.message}") }
        }
    }

    private fun endMs(day: LocalDate) =
        day
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private fun failed(
        target: String,
        kind: FlowKind,
        day: LocalDate,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not fetch the ${kind.capability} of $target for $day: ${cause.message}")
        return ExitCodes.USER_ERROR
    }

    private const val DAY_MS = 86_400_000L
}
