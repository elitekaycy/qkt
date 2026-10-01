package com.qkt.backtest

import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.instrument.optionSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The chain counterpart of tick coverage: every UTC day an option contract trades (up to its expiry)
 * and every day of a chain analytics stream or option root feed must hold a stored chain day of the root's declared
 * series, or the run fails naming the fetch that fills the gap (unless incomplete data is allowed,
 * which warns instead).
 */
internal object OptionChainCoverage {
    private class Need(
        val symbol: String,
        val root: String,
        val lastDay: LocalDate,
    )

    /** Checks the option contracts and chain analytics streams among [symbols] over the UTC days [from]..[to]. */
    fun ensure(
        instruments: InstrumentRegistry,
        symbols: Collection<String>,
        from: LocalDate,
        to: LocalDate,
        allowIncomplete: Boolean,
    ) {
        val options = instruments.options() ?: return
        val contracts =
            instruments.optionSymbols(symbols).map { symbol ->
                val terms =
                    requireNotNull(
                        instruments.lookup(symbol)?.derivative as? OptionTerms,
                    ) { "$symbol has no option terms" }
                Need(
                    symbol,
                    terms.root,
                    minOf(to, Instant.ofEpochMilli(terms.expiryMs).atZone(ZoneOffset.UTC).toLocalDate()),
                )
            }
        val streams =
            symbols.mapNotNull { s ->
                when {
                    s.startsWith(
                        ChainAnalyticsSymbol.PREFIX,
                    ) -> Need(s, ChainAnalyticsSymbol.parse(s).getOrThrow().root, to)
                    s.startsWith(OptionRootSymbol.PREFIX) -> Need(s, OptionRootSymbol.parse(s).getOrThrow().root, to)
                    else -> null
                }
            }
        for (need in contracts + streams) {
            val root = options.root(need.root) ?: continue
            val source = root.chains ?: continue
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(need.lastDay) }.toList()
            val store = ChainSnapshotStore(requireNotNull(options.dataRoot), source)
            val missing = days.filterNot { store.hasDay(root.root, it) }
            val series = source.name.lowercase()
            System.err.println(
                "qkt: chain coverage ${need.symbol} ${days.size - missing.size}/${days.size} days ($series chain)",
            )
            if (missing.isEmpty()) continue
            val gap =
                "missing $series chain days for ${need.symbol}: ${missing.joinToString()}; fetch them with: " +
                    "qkt fetch ${root.root} --chains --from ${missing.first()} --to ${missing.last()}"
            if (!allowIncomplete) throw IncompleteDataException(gap)
            System.err.println("qkt: WARNING — running with incomplete data: $gap")
        }
    }
}
