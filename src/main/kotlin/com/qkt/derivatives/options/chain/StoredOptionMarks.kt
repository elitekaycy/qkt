package com.qkt.derivatives.options.chain

import com.qkt.instrument.InstrumentRegistry

/**
 * Option marks a backtest replays from the roots' declared chain series ([ChainView]): an option's quote in
 * its root's newest snapshot at or before the instant, the snapshot chain analytics and structures read at
 * that instant. A root that declares no chain series has none, and says so.
 */
class StoredOptionMarks(
    private val instruments: InstrumentRegistry,
) : OptionMarks {
    private val view = ChainView(instruments)

    override fun at(
        qktSymbol: String,
        atMs: Long,
    ): ChainQuote? {
        val options = instruments.options() ?: return null
        val root = options.optionRoot(qktSymbol) ?: return null
        val name = options.venueName(qktSymbol) ?: return null
        val snapshot = synchronized(view) { view.latest(root.root, atMs) } ?: return null
        return snapshot.quotes.firstOrNull { it.contract == name }
    }

    override fun problem(qktSymbol: String): String? {
        val root = instruments.options()?.optionRoot(qktSymbol) ?: return "it is not a catalogued option"
        return if (root.chains == null) {
            "its root ${root.root} declares no chain series to replay (chains: book | trade)"
        } else {
            null
        }
    }
}
