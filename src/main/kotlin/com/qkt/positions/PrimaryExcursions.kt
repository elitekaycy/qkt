package com.qkt.positions

import com.qkt.marketdata.Candle
import com.qkt.persistence.PersistedExcursion
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-(strategyId, symbol) excursion trackers for the current PRIMARY leg. Maintained in
 * sync with the leg-book by [sync] after every fill, and updated on each market tick via
 * [onTick]. Reads land via [mfeFor], which backs the DSL accessor `POSITION.<stream>.mfe`, and
 * [maeFor], which backs `POSITION.<stream>.mae`. Owned and driven by [StrategyPositionTracker].
 *
 * Same-direction averaging fills re-anchor the tracker to the new weighted entry —
 * MFE resets to zero from the new reference point, matching the "favorable excursion
 * from current best-estimate entry" semantic.
 */
internal class PrimaryExcursions(
    private val legBooks: StrategyLegBooks,
    private val persistor: StatePersistor,
    private val excursionPersistIntervalMs: Long,
    private val clock: () -> Long,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(StrategyPositionTracker::class.java)

    private val primaryMfeTrackers: MutableMap<Pair<String, String>, LegMfe> = ConcurrentHashMap()

    private class LegMfe(
        val legId: String,
        val tracker: MfeTracker,
    ) {
        /** Wall-clock of the last excursion save; the throttle in [persistExcursion] reads it. */
        @Volatile
        var lastPersistedAt: Long = Long.MIN_VALUE / 2
    }

    /** Seed the tracker of a just-restored [book] with the marks saved before the restart. */
    fun restore(
        strategyId: String,
        symbol: String,
        book: LegBook,
    ) {
        val tracked = primaryMfeTrackers[Pair(strategyId, symbol)] ?: return
        val saved = runCatching { persistor.loadExcursion(strategyId, symbol) }.getOrNull() ?: return
        val leg = book.leg(tracked.legId) ?: return
        if (saved.legId != leg.legId || saved.side != leg.side || saved.entryPrice.compareTo(leg.entryPrice) != 0) {
            return
        }
        tracked.tracker.seed(saved.mfe, saved.mae, saved.adverseExtremePrice)
        log.info(
            "restored excursion for {} {} leg={} mfe={} mae={}",
            strategyId,
            symbol,
            leg.legId,
            saved.mfe.toPlainString(),
            saved.mae.toPlainString(),
        )
    }

    /** See [StrategyPositionTracker.extendExcursion]. */
    fun extend(
        strategyId: String,
        symbol: String,
        candles: List<Candle>,
    ) {
        val key = Pair(strategyId, symbol)
        val tracked = primaryMfeTrackers[key] ?: return
        val leg = legBooks.book(strategyId, symbol)?.leg(tracked.legId) ?: return
        var used = 0
        for (candle in candles) {
            if (candle.startTime < leg.openedAt) continue
            tracked.tracker.observeRange(candle.high, candle.low)
            used++
        }
        if (used > 0) {
            persistExcursion(strategyId, symbol, tracked, force = true)
            log.info(
                "extended excursion for {} {} leg={} from {} downtime bars: mfe={} mae={}",
                strategyId,
                symbol,
                leg.legId,
                used,
                tracked.tracker.value().toPlainString(),
                tracked.tracker.mae().toPlainString(),
            )
        }
    }

    private fun persistExcursion(
        strategyId: String,
        symbol: String,
        tracked: LegMfe,
        force: Boolean = false,
    ) {
        val now = clock()
        if (!force && now - tracked.lastPersistedAt < excursionPersistIntervalMs) return
        val leg = legBooks.book(strategyId, symbol)?.leg(tracked.legId) ?: return
        tracked.lastPersistedAt = now
        runCatching {
            persistor.saveExcursion(
                strategyId,
                symbol,
                PersistedExcursion(
                    legId = leg.legId,
                    side = leg.side,
                    entryPrice = leg.entryPrice,
                    mfe = tracked.tracker.value(),
                    mae = tracked.tracker.mae(),
                    adverseExtremePrice = tracked.tracker.adverseExtremePrice(),
                ),
            )
        }
    }

    /** See [StrategyPositionTracker.onTick]. */
    fun onTick(
        symbol: String,
        price: BigDecimal,
    ) {
        if (primaryMfeTrackers.isEmpty()) return
        for ((key, lm) in primaryMfeTrackers) {
            if (key.second != symbol) continue
            val mfeBefore = lm.tracker.value()
            val maeBefore = lm.tracker.mae()
            lm.tracker.onTick(price)
            // A new extreme is worth keeping across a restart (#1158); ties and pullbacks are not.
            if (lm.tracker.value() > mfeBefore || lm.tracker.mae() > maeBefore) {
                persistExcursion(key.first, symbol, lm)
            }
        }
    }

    fun mfeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = primaryMfeTrackers[Pair(strategyId, symbol)]?.tracker?.value()

    fun maeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = primaryMfeTrackers[Pair(strategyId, symbol)]?.tracker?.mae()

    fun adverseExtremePriceFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = primaryMfeTrackers[Pair(strategyId, symbol)]?.tracker?.adverseExtremePrice()

    /** Re-anchor (or drop) the (strategyId, symbol) tracker to the book's current entry leg. */
    fun sync(
        strategyId: String,
        symbol: String,
    ) {
        val key = Pair(strategyId, symbol)
        val primary = legBooks.book(strategyId, symbol)?.let { entryLeg(it) }
        if (primary == null) {
            primaryMfeTrackers.remove(key)
            return
        }
        val existing = primaryMfeTrackers[key]
        if (existing == null || existing.legId != primary.legId) {
            primaryMfeTrackers[key] = LegMfe(primary.legId, MfeTracker(primary.side, primary.entryPrice))
        }
    }
}

/**
 * The leg `POSITION.<stream>.mfe` measures: the PRIMARY when the book has one, otherwise the
 * oldest parentless leg. Hedging venues open every plain BUY/SELL as an INDEPENDENT leg, so
 * without the fallback the excursion accessors would sit at zero for the whole trade.
 */
private fun entryLeg(book: LegBook): PositionLeg? =
    book.primary()
        ?: book
            .all()
            .filter { it.parentLegId == null && it.role != LegRole.STACK }
            .minWithOrNull(compareBy({ it.openedAt }, { it.legId }))
