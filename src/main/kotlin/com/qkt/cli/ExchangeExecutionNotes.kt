package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.dsl.compile.isObservationSymbol

/** What the exchange simulator modelled for the run's [futures] symbols. */
internal fun futuresExecutionNote(futures: Set<String>): String {
    val names = futures.sorted().joinToString(", ")
    val verb = if (futures.size == 1) "fills" else "fill"
    return "qkt: note: $names $verb on the exchange simulator: market orders at the executable price plus the " +
        "run's slippage model, the root's fees on every fill, rolls booked as roll costs."
}

/** What the option venue modelled for the run's [options] symbols. */
internal fun optionsExecutionNote(options: Set<String>): String {
    val names = options.sorted().joinToString(", ")
    val verb = if (options.size == 1) "fills" else "fill"
    return "qkt: note: $names $verb on the option venue: at the next chain snapshot's bid or ask (a trade-built " +
        "chain's quotes are the declared spread around the mark), never at mid; capped venue fees; held contracts " +
        "cash-settle at the catalog's delivery price."
}

/**
 * Tells the operator what each part of the run's execution modelled: the futures and option venues for
 * their symbols, and the paper broker's optimism when it fills any traded symbol.
 */
internal fun printExecutionNotes(
    symbols: List<String>,
    futures: Set<String>,
    options: Set<String>,
    brokerKind: BrokerKind,
) {
    if (futures.isNotEmpty()) System.err.println(futuresExecutionNote(futures))
    if (options.isNotEmpty()) System.err.println(optionsExecutionNote(options))
    val paperTraded =
        symbols.any {
            it !in futures &&
                it !in options &&
                !isObservationSymbol(it) &&
                !it.startsWith(OptionRootSymbol.PREFIX)
        }
    if (brokerKind == BrokerKind.PAPER && paperTraded) {
        System.err.println(
            "qkt: note: paper broker fills at mid with no spread/slippage — results are optimistic. " +
                "Use --broker mt5-sim and set commissionPerLot + slippagePoints in instruments.yaml " +
                "for cost-realistic backtests.",
        )
    }
}
