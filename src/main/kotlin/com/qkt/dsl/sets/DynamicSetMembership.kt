package com.qkt.dsl.sets

import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.CatalogValue
import com.qkt.instrument.universe.MarketFacts
import com.qkt.instrument.universe.MembershipStep
import com.qkt.instrument.universe.SetMembership
import com.qkt.instrument.universe.UniverseResolver

/**
 * The point-in-time membership of one `DYNAMIC` filtered set (runtime membership plan, step 4): at each close of the
 * set's `EVERY` window (epoch aligned, [windowMs]) the venue's [rows] alive at that close are filtered and ranked as
 * of it by the one [UniverseResolver] start-resolved sets use, the top n become the members, and [SetMembership]
 * turns them into joins, leaves and sticky members. A pure function of the catalog, the instant and the held
 * members, so a backtest replays it exactly and a live run steps alike.
 */
class DynamicSetMembership(
    private val spec: SymbolSetSpec.Filtered,
    private val rows: List<InstrumentMeta>,
    val windowMs: Long,
) {
    private val membership = SetMembership()
    private var nextCloseMs = Long.MIN_VALUE

    /**
     * The qkt symbols (`VENUE:SYMBOL`) of every active member after the latest step, sticky-held ones included: an
     * immutable snapshot replaced at each step, so a reader on another thread (a set cap at order time) reads it
     * without locking or allocating. Empty before the first step.
     */
    @Volatile
    var activeSymbols: Set<String> = emptySet()
        private set

    init {
        require(spec.moves) { "${spec.canonical} is resolved once, at start; it has no runtime membership" }
        require(windowMs > 0) { "a set's window must be positive: $windowMs ms" }
    }

    /** The venue whose bare symbols the members are. */
    val venue: String get() = spec.venue

    /** True when the set filters or ranks on as-of market fields, so a run must hand it [MarketFacts]. */
    val readsMarket: Boolean get() = spec.readsMarket

    /**
     * The members at [closeMs]: the venue's rows alive then, filtered and ranked as of it with market fields from
     * [facts], top n, bare symbols.
     */
    fun membersAt(
        closeMs: Long,
        facts: MarketFacts = MarketFacts.NONE,
    ): List<String> =
        UniverseResolver
            .members(
                rows.filter { alive(it, closeMs) },
                spec.venue,
                spec.filter,
                spec.top,
                spec.by,
                spec.descending,
                closeMs,
                facts,
            ).map { it.qktSymbol.substringAfter(':') }

    /**
     * The step of the window close at or before [nowMs], with the market figures [facts] gives for that close and the
     * members [held] then reports holding, the first time an instant reaches that close; null until the next close.
     */
    fun at(
        nowMs: Long,
        facts: (closeMs: Long) -> MarketFacts = { MarketFacts.NONE },
        held: () -> Set<String>,
    ): Evaluation? {
        if (nowMs < nextCloseMs) return null
        val closeMs = nowMs - Math.floorMod(nowMs, windowMs)
        nextCloseMs = closeMs + windowMs
        val step = membership.step(membersAt(closeMs, facts(closeMs)), held())
        activeSymbols = step.active.mapTo(LinkedHashSet()) { "${spec.venue}:$it" }
        return Evaluation(closeMs, step)
    }

    /** One evaluation: the window close it is as of, and the step it produced. */
    data class Evaluation(
        val closeMs: Long,
        val step: MembershipStep,
    )

    companion object {
        /**
         * Whether [meta] is listed at [atMs]: an event contract once it opened, any contract until it expires (a row
         * without an expiry never does). The catalog's own rows are what was listed; nothing else is inferred.
         */
        fun alive(
            meta: InstrumentMeta,
            atMs: Long,
        ): Boolean {
            val opened = (meta.derivative as? EventTerms)?.let { it.openAtMs <= atMs } ?: true
            val expiry = (CatalogField.EXPIRY_AT.read(meta, atMs) as? CatalogValue.Num)?.value?.toLong()
            return opened && (expiry == null || atMs < expiry)
        }
    }
}
