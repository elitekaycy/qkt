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
    val spec: SymbolSetSpec.Filtered,
    listed: List<InstrumentMeta>,
    val windowMs: Long,
) {
    /**
     * The catalog rows the set ranks; the event contracts among them also give its strike ladders. A live session
     * replaces them as it subscribes more ([list]).
     */
    @Volatile
    var rows: List<InstrumentMeta> = listed
        private set

    /**
     * Ranks only [next] from now on: a live session lists the venue rows whose market data it serves, so a member is
     * never picked that the session cannot feed. Safe from another thread; the next evaluation reads the new rows.
     */
    fun list(next: List<InstrumentMeta>) {
        rows = next
    }

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
     * [facts], top n, bare symbols. A slot universe ranks each family through its front contract and names the
     * family's slot (`KXINXU@front.p70`).
     */
    fun membersAt(
        closeMs: Long,
        facts: MarketFacts = MarketFacts.NONE,
    ): List<String> {
        val alive = rows.filter { alive(it, closeMs) }
        val slot = spec.slot ?: return rank(alive, closeMs, facts).map { it.qktSymbol.substringAfter(':') }
        if (spec.ladder) {
            val ladder = SlotUniverse.ladder(alive, spec.venue, spec.family, slot, closeMs)
            return rank(ladder, closeMs, facts).map { it.qktSymbol.substringAfter(':') }
        }
        val fronts = SlotUniverse.fronts(alive)
        return rank(fronts.keys.toList(), closeMs, facts).map { SlotUniverse.member(fronts.getValue(it), slot) }
    }

    private fun rank(
        rows: List<InstrumentMeta>,
        closeMs: Long,
        facts: MarketFacts,
    ): List<InstrumentMeta> =
        UniverseResolver
            .members(
                rows,
                spec.venue,
                spec.filter,
                spec.top,
                spec.by,
                spec.descending,
                closeMs,
                facts,
            )

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
         * Every member some close of a [windowMs] window in [fromMs]..[toMs] picks from [rows] for [spec], in symbol
         * order: for a set reading market fields, every row listed then whose catalog part may pass ([watchable]),
         * since which rank depends on figures only the run sees.
         */
        fun candidatesOver(
            spec: SymbolSetSpec.Filtered,
            rows: List<InstrumentMeta>,
            windowMs: Long,
            fromMs: Long,
            toMs: Long,
        ): List<String> {
            val membership = DynamicSetMembership(spec, rows, windowMs)
            val picked = sortedSetOf<String>()
            var closeMs = fromMs - Math.floorMod(fromMs, windowMs)
            while (closeMs <= toMs) {
                picked += if (spec.readsMarket) watchable(spec, rows, closeMs) else membership.membersAt(closeMs)
                closeMs += windowMs
            }
            return picked.toList()
        }

        private fun watchable(
            spec: SymbolSetSpec.Filtered,
            rows: List<InstrumentMeta>,
            closeMs: Long,
        ): List<String> =
            rows
                .filter { alive(it, closeMs) && spec.filter.mayMatch(it, closeMs) }
                .map { it.qktSymbol.substringAfter(':') }

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
