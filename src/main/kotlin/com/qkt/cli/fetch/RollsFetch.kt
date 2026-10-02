package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.ExitCodes
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.RollHistoryBuilder
import com.qkt.derivatives.futures.RollSchedule
import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.marketdata.store.LocalBarStore
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `qkt fetch VENUE:ROOT --rolls`: measures the root's roll history from stored 1m bars, fetching the
 * roll days it is missing through [fetchDay], and writes `contracts/<VENUE>/<ROOT>.rolls.json`.
 */
internal object RollsFetch {
    private const val TIMEFRAME = "1m"

    /** Builds and stores [target]'s roll history; returns a process exit code. */
    fun run(
        target: String,
        dataRoot: Path,
        instruments: Path?,
        fetchDay: (contract: String, day: LocalDate) -> Unit,
    ): Int =
        try {
            build(target, dataRoot, instruments, fetchDay)
        } catch (e: IOException) {
            failed(target, e)
        } catch (e: IllegalStateException) {
            failed(target, e)
        } catch (e: IllegalArgumentException) {
            failed(target, e)
        }

    /** Fetches one UTC day of 1m bars for [venue]:[contract] into [store] when it is not already there. */
    fun fetchOneDay(
        fetcher: BarFetcher,
        store: LocalBarStore,
        venue: String,
        contract: String,
        day: LocalDate,
    ) {
        val from = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val bars = fetcher.fetch(contract, TimeWindow.parse(TIMEFRAME), TimeRange(from, from.plusSeconds(86_400L)))
        if (bars.isEmpty()) return
        store.writeDay(venue, contract, TIMEFRAME, day, bars)
        store.recordDay(venue, contract, TIMEFRAME, day)
    }

    private fun build(
        target: String,
        dataRoot: Path,
        instruments: Path?,
        fetchDay: (String, LocalDate) -> Unit,
    ): Int {
        val root =
            FuturesRootsFile.load(instruments ?: dataRoot.resolve("instruments.yaml")).firstOrNull { it.root == target }
                ?: error("$target is not declared under 'futures:' in instruments.yaml")
        val policy = requireNotNull(root.roll) { "$target has no roll policy (add roll: to its futures entry)" }
        val catalog =
            ContractCatalogStore(dataRoot).read(target)
                ?: error("no catalog for $target; run qkt fetch $target --catalog")
        val store = LocalBarStore(dataRoot)
        val schedule = RollSchedule(catalog.contracts, policy)
        for (t in schedule.transitions) {
            val day = Instant.ofEpochMilli(t.atMs).atZone(ZoneOffset.UTC).toLocalDate()
            for (offset in ContinuousSelector.entries.map { it.offset }) {
                for (index in listOf(t.fromIndex + offset, t.toIndex + offset)) {
                    val contract = schedule.contracts.getOrNull(index)?.symbol ?: continue
                    for (d in listOf(day.minusDays(1), day)) {
                        if (!store.hasDay(root.venue, contract, TIMEFRAME, d)) fetchDay(contract, d)
                    }
                }
            }
        }
        val history =
            RollHistoryBuilder {
                contract,
                day,
                ->
                store.readDay(root.venue, contract, TIMEFRAME, day)
            }.build(root, catalog)
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
