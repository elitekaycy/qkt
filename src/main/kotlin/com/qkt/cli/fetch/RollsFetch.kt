package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.RollHistoryBuilder
import com.qkt.derivatives.futures.RollPricing
import com.qkt.derivatives.futures.RollSchedule
import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.LocalBarStore
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:ROOT --rolls [--tf <tf>]`: measures the root's roll history from stored bars of `--tf`
 * (default 1m; a root with only daily bars, as a vendor archive gives them, uses `--tf 1d`), fetched or
 * built ([RollBars]), fetching the roll days it cannot price through [fetchDay], and writes
 * `contracts/<VENUE>/<ROOT>.rolls.json`.
 */
internal object RollsFetch {
    private const val DEFAULT_TIMEFRAME = "1m"
    private const val DAY_MS = 86_400_000L

    /** Runs `--rolls` from command-line [args], fetching through [broker]'s bar source when it has one. */
    fun forArgs(
        target: String,
        broker: String,
        args: Args,
    ): Int {
        val timeframe =
            try {
                TimeWindow.parse(args.option("tf") ?: DEFAULT_TIMEFRAME)
            } catch (e: IllegalStateException) {
                System.err.println("qkt: invalid --tf '${args.option("tf")}': ${e.message}")
                return ExitCodes.ARG_ERROR
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: invalid --tf '${args.option("tf")}': ${e.message}")
                return ExitCodes.ARG_ERROR
            }
        val dataRoot = DataRoot.forDataRoot(args.option("data-root"))
        val store = LocalBarStore(root = dataRoot)
        val fetcher = lazy { buildFetcher(broker, args.option("config")).also { if (it == null) noSource(broker) } }
        return run(target, dataRoot, args.option("instruments")?.let(Path::of), timeframe) { contract, day ->
            fetcher.value?.let { fetchOneDay(it, store, broker, contract, day, timeframe) }
        }
    }

    private fun noSource(broker: String) =
        System.err.println("qkt fetch: no bar source for $broker; measuring from stored bars only")

    /** Builds and stores [target]'s roll history from [timeframe] bars; returns a process exit code. */
    fun run(
        target: String,
        dataRoot: Path,
        instruments: Path?,
        timeframe: TimeWindow = TimeWindow.parse(DEFAULT_TIMEFRAME),
        fetchDay: (contract: String, day: LocalDate) -> Unit,
    ): Int =
        try {
            build(target, dataRoot, instruments, timeframe, fetchDay)
        } catch (e: IOException) {
            failed(target, e)
        } catch (e: IllegalStateException) {
            failed(target, e)
        } catch (e: IllegalArgumentException) {
            failed(target, e)
        }

    /** Fetches one UTC day of [timeframe] bars for [venue]:[contract] into [store]. */
    fun fetchOneDay(
        fetcher: BarFetcher,
        store: LocalBarStore,
        venue: String,
        contract: String,
        day: LocalDate,
        timeframe: TimeWindow = TimeWindow.parse(DEFAULT_TIMEFRAME),
    ) {
        val from = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val bars = fetcher.fetch(contract, timeframe, TimeRange(from, from.plusSeconds(86_400L)))
        if (bars.isEmpty()) return
        store.writeDay(venue, contract, timeframe.canonicalSpec(), day, bars)
        store.recordDay(venue, contract, timeframe.canonicalSpec(), day)
    }

    private fun build(
        target: String,
        dataRoot: Path,
        instruments: Path?,
        timeframe: TimeWindow,
        fetchDay: (String, LocalDate) -> Unit,
    ): Int {
        require(timeframe.durationMs <= DAY_MS) {
            "--rolls prices rolls from bars of 1d or shorter, not ${timeframe.canonicalSpec()}"
        }
        val root =
            FuturesRootsFile.load(instruments ?: dataRoot.resolve("instruments.yaml")).firstOrNull { it.root == target }
                ?: error("$target is not declared under 'futures:' in instruments.yaml")
        val policy = requireNotNull(root.roll) { "$target has no roll policy (add roll: to its futures entry)" }
        val catalog =
            ContractCatalogStore(dataRoot).read(target)
                ?: error("no catalog for $target; run qkt fetch $target --catalog")
        val stored = RollBars(dataRoot, root.venue, timeframe)
        val bars = stored::readDay
        val schedule = RollSchedule(catalog.contracts, policy)
        for (t in schedule.transitions) {
            val days = RollPricing.days(t.atMs, timeframe.durationMs)
            for (offset in ContinuousSelector.entries.map { it.offset }) {
                for (index in listOf(t.fromIndex + offset, t.toIndex + offset)) {
                    val contract = schedule.contracts.getOrNull(index)?.symbol ?: continue
                    if (RollPricing.closeAtOrBefore(days.flatMap { bars(contract, it) }, t.atMs) != null) continue
                    days.filterNot { stored.hasDay(contract, it) }.forEach {
                        fetchDay(contract, it)
                    }
                }
            }
        }
        val history = RollHistoryBuilder(timeframe.durationMs, bars).build(root, catalog)
        RollHistoryStore(dataRoot).write(history)
        report(target, schedule, history, RollHistoryStore(dataRoot).path(target))
        return ExitCodes.SUCCESS
    }

    private fun report(
        target: String,
        schedule: RollSchedule,
        history: RollHistory,
        file: Path,
    ) {
        val front =
            schedule.transitions.filter { t ->
                history.find(t.atMs, schedule.contracts[t.fromIndex].symbol, schedule.contracts[t.toIndex].symbol) !=
                    null
            }
        println("qkt fetch: measured ${front.size} of ${schedule.transitions.size} front rolls for $target -> $file")
        if (front.isEmpty()) return
        val first = Instant.ofEpochMilli(front.first().atMs)
        println("  from the $first roll to the ${Instant.ofEpochMilli(front.last().atMs)} roll")
        val gap = schedule.transitions.lastOrNull { it.atMs < front.first().atMs } ?: return
        val pair = "${schedule.contracts[gap.fromIndex].symbol} -> ${schedule.contracts[gap.toIndex].symbol}"
        println("  starts after the ${Instant.ofEpochMilli(gap.atMs)} roll ($pair), which could not be measured")
    }

    private fun failed(
        target: String,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not build the roll history for $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
