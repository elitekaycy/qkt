package com.qkt.instrument

/** What a run needs about the event families it declares beyond each contract's metadata. */
interface EventDirectory {
    /** The family declared as [root] (`KALSHI:KXINXU`), or null. */
    fun family(root: String): EventFamily?

    /** The catalogued contracts of family [root] as qkt symbols, in expiry order; empty when it has no catalog. */
    fun contracts(root: String): List<String>

    /** The contracts of family [root] alive at [atMs]: listed (`openAtMs <= atMs`), not yet expired (`< expiryMs`). */
    fun aliveAt(
        root: String,
        atMs: Long,
    ): List<String>
}
