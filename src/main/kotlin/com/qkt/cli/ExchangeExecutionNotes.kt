package com.qkt.cli

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
