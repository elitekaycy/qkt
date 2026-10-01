package com.qkt.backtest

import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.instrument.optionSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The option counterpart of tick coverage: every UTC day of a run up to a contract's expiry must hold
 * a stored chain day of its root's declared series, or the run fails naming the fetch that fills the
 * gap (unless incomplete data is allowed, which warns instead). Days after expiry need no chain.
 */
internal object OptionChainCoverage {
    /** Checks the option contracts among [symbols] over the UTC days [from]..[to]. */
    fun ensure(
        instruments: InstrumentRegistry,
        symbols: Collection<String>,
        from: LocalDate,
        to: LocalDate,
        allowIncomplete: Boolean,
    ) {
        val options = instruments.options() ?: return
        for (symbol in instruments.optionSymbols(symbols)) {
            val root = requireNotNull(options.optionRoot(symbol))
            val source = root.chains ?: continue
            val terms =
                requireNotNull(instruments.lookup(symbol)?.derivative as? OptionTerms) { "$symbol has no option terms" }
            val expiry = terms.expiryMs
            val last = minOf(to, Instant.ofEpochMilli(expiry).atZone(ZoneOffset.UTC).toLocalDate())
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
            val store = ChainSnapshotStore(requireNotNull(options.dataRoot), source)
            val missing = days.filterNot { store.hasDay(root.root, it) }
            val series = source.name.lowercase()
            System.err.println(
                "qkt: chain coverage $symbol ${days.size - missing.size}/${days.size} days ($series chain)",
            )
            if (missing.isEmpty()) continue
            val gap =
                "missing $series chain days for $symbol: ${missing.joinToString()}; fetch them with: " +
                    "qkt fetch ${root.root} --chains --from ${missing.first()} --to ${missing.last()}"
            if (!allowIncomplete) throw IncompleteDataException(gap)
            System.err.println("qkt: WARNING — running with incomplete data: $gap")
        }
    }
}
