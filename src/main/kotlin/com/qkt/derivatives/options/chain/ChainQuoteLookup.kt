package com.qkt.derivatives.options.chain

import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionDirectory
import com.qkt.instrument.OptionRoot
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The stored chain quote of an option at an exact instant, from its root's declared chain series.
 * Asked only for the instant being replayed, it never reads ahead; each root keeps its latest day
 * decoded, so a replay moving forward reads every day file once.
 */
class ChainQuoteLookup(
    private val dataRoot: Path,
    instruments: InstrumentRegistry,
) {
    private val directory: OptionDirectory? = instruments.options()
    private val days = HashMap<String, Day>()

    private class Day(
        val date: LocalDate,
        val quotes: Map<Long, Map<String, ChainQuote>>,
    )

    /** [qktSymbol]'s quote in the snapshot taken at [atMs], or null when there is none. */
    fun quoteAt(
        qktSymbol: String,
        atMs: Long,
    ): ChainQuote? {
        val options = directory ?: return null
        val root = options.optionRoot(qktSymbol) ?: return null
        val name = options.venueName(qktSymbol) ?: return null
        return day(root, Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()).quotes[atMs]?.get(name)
    }

    private fun day(
        root: OptionRoot,
        date: LocalDate,
    ): Day {
        days[root.root]?.takeIf { it.date == date }?.let { return it }
        val source =
            requireNotNull(root.chains) { "${root.root} declares no chain series to trade on (chains: trade | book)" }
        val snapshots = ChainSnapshotStore(dataRoot, source).readDay(root.root, date)
        return Day(date, snapshots.associate { s -> s.atMs to s.quotes.associateBy { it.contract } }).also {
            days[root.root] =
                it
        }
    }
}
