package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.openAccounts
import com.qkt.common.Clock
import com.qkt.marketdata.marks.MarkHistorySource
import com.qkt.marketdata.marks.MarkStore
import com.qkt.marketdata.store.DataRoot
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:CONTRACT --marks --tf 1m --from D --to D`: stores the contract's mark and index history,
 * one sample per `--tf` window (the venue's last report in it), as one file per UTC day under
 * `<dataRoot>/marks/<VENUE>/<NAME>/<tf>/`, for backtests to replay. A day is written whole, replacing what
 * was stored, and only once it is over by [Clock]; a day the venue reported nothing in is stored empty. The
 * history comes from the `brokers:` account named after the venue (a `type: gateway` account, whose gateway
 * must declare `mark_prices`).
 */
internal object MarksFetch {
    /** Fetches [target]'s marks; returns a process exit code. */
    fun run(
        target: String,
        args: Args,
        clock: Clock,
    ): Int {
        val tf = args.option("tf") ?: return usage("--marks needs --tf, the sampling window (e.g. 1m, 1h)")
        val windowMs = runCatching { TimeWindow.parse(tf).durationMs }.getOrNull()
        if (windowMs == null || windowMs % MINUTE_MS != 0L || DAY_MS % windowMs != 0L) {
            return usage("--tf $tf must be whole minutes dividing a day for --marks")
        }
        val (from, to) =
            resolveFetchRange(args.option("from"), args.option("to"), args.option("last")) ?: return ExitCodes.ARG_ERROR
        val venue = target.substringBefore(':')
        val source =
            sourceFor(venue, args.option("config")) ?: run {
                System.err.println(
                    "qkt: no mark history for '$venue' (a type: gateway account of that name in --config)",
                )
                return ExitCodes.USER_ERROR
            }
        val store = MarkStore(DataRoot.forDataRoot(args.option("data-root")))
        val over = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) && endMs(it) <= clock.now() }
        var stored = 0
        var samples = 0
        for (day in over) {
            val marks =
                try {
                    source.marks(target, windowMs, endMs(day) - DAY_MS, endMs(day))
                } catch (e: IOException) {
                    return failed(target, day, e)
                } catch (e: RuntimeException) {
                    return failed(target, day, e)
                }
            store.write(target, windowMs, day, marks)
            stored++
            samples += marks.size
        }
        val skipped =
            if (stored.toLong() <
                from.datesUntil(to.plusDays(1)).count()
            ) {
                " (days not yet over are not stored)"
            } else {
                ""
            }
        println(
            "qkt fetch: $samples marks for $target every $tf over $stored day(s)$skipped -> ${store.dir(
                target,
                windowMs,
            )}",
        )
        return ExitCodes.SUCCESS
    }

    private fun sourceFor(
        venue: String,
        configOption: String?,
    ): MarkHistorySource? {
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)?.markHistory
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

    private fun usage(message: String): Int {
        System.err.println("qkt: $message")
        return ExitCodes.ARG_ERROR
    }

    private fun failed(
        target: String,
        day: LocalDate,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not fetch the marks of $target for $day: ${cause.message}")
        return ExitCodes.USER_ERROR
    }

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L
}
