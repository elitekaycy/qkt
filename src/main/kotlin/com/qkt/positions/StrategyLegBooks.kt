package com.qkt.positions

import com.qkt.common.Money
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * The per-(strategy, symbol) [LegBook] store behind [StrategyPositionTracker], with its durable
 * copy in [persistor] and the read queries of [StrategyLegReads]. Owned by the tracker, which
 * remains the only writer: its booking collaborators mutate books through this store only when
 * the tracker calls them.
 */
internal class StrategyLegBooks(
    private val persistor: StatePersistor,
) : StrategyLegReads {
    private val byStrategy: MutableMap<String, MutableMap<String, LegBook>> = ConcurrentHashMap()

    fun book(
        strategyId: String,
        symbol: String,
    ): LegBook? = byStrategy[strategyId]?.get(symbol)

    fun booksOf(strategyId: String): MutableMap<String, LegBook>? = byStrategy[strategyId]

    fun booksOrCreate(strategyId: String): MutableMap<String, LegBook> =
        byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }

    /** Every strategy's symbol-to-book map, as a live view. */
    fun strategyBooks(): Collection<MutableMap<String, LegBook>> = byStrategy.values

    /** Strategies holding a book on [symbol]. */
    fun holdersOf(symbol: String): List<Map.Entry<String, MutableMap<String, LegBook>>> =
        byStrategy.entries.filter { (_, books) -> books.containsKey(symbol) }

    /** Save the (strategyId, symbol) book, an empty one when it no longer exists. */
    fun persist(
        strategyId: String,
        symbol: String,
    ) {
        val book = byStrategy[strategyId]?.get(symbol) ?: LegBook(symbol)
        runCatching { persistor.saveLegBook(strategyId, symbol, book) }
    }

    /** Load the persisted book into memory; null when there is no non-empty record. */
    fun restore(
        strategyId: String,
        symbol: String,
    ): LegBook? {
        val persisted = runCatching { persistor.loadLegBook(strategyId, symbol) }.getOrNull() ?: return null
        if (persisted.legs.isEmpty()) return null
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(symbol) { LegBook(symbol) }
        for (leg in persisted.legs) book.add(leg.toPositionLeg())
        return book
    }

    override fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position? = byStrategy[strategyId]?.get(symbol)?.netView()

    override fun positionsFor(strategyId: String): Map<String, Position> {
        val books = byStrategy[strategyId] ?: return emptyMap()
        // Single pass straight into the result map; the previous mapNotNull{}.toMap() allocated an
        // intermediate List of Pairs and rehashed, per call, on the per-tick position-read path.
        return buildMap(books.size) {
            for ((sym, book) in books) book.netView()?.let { put(sym, it) }
        }
    }

    override fun allByStrategy(): Map<String, Map<String, Position>> =
        byStrategy.mapValues { (_, books) ->
            books.mapNotNull { (sym, book) -> book.netView()?.let { sym to it } }.toMap()
        }

    override fun allLegsFor(strategyId: String): List<PositionLeg> =
        byStrategy[strategyId]?.values?.flatMap { it.all() } ?: emptyList()

    override fun legBookFor(
        strategyId: String,
        symbol: String,
    ): LegBook? = byStrategy[strategyId]?.get(symbol)

    override fun legById(
        strategyId: String,
        legId: String,
    ): PositionLeg? =
        byStrategy[strategyId]
            ?.values
            ?.firstNotNullOfOrNull { book -> book.all().firstOrNull { it.legId == legId } }

    override fun ticketForLeg(
        strategyId: String,
        legId: String,
    ): String? =
        byStrategy[strategyId]?.values?.firstNotNullOfOrNull { book ->
            book.all().firstOrNull { it.legId == legId }?.brokerTicket
        }

    override fun ticketForPrimary(
        strategyId: String,
        symbol: String,
    ): String? = byStrategy[strategyId]?.get(symbol)?.primary()?.brokerTicket

    override fun openCountFor(
        strategyId: String,
        symbol: String,
    ): Int = byStrategy[strategyId]?.get(symbol)?.size() ?: 0

    override fun longCountFor(
        strategyId: String,
        symbol: String,
    ): Int = byStrategy[strategyId]?.get(symbol)?.longCount() ?: 0

    override fun shortCountFor(
        strategyId: String,
        symbol: String,
    ): Int = byStrategy[strategyId]?.get(symbol)?.shortCount() ?: 0

    override fun grossFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal = byStrategy[strategyId]?.get(symbol)?.grossQuantity() ?: Money.ZERO

    override fun driftFor(
        symbol: String,
        brokerView: PositionProvider,
    ): BigDecimal {
        val strategySum =
            byStrategy.values.fold(Money.ZERO) { acc, books ->
                acc.add(books[symbol]?.netQuantity() ?: Money.ZERO)
            }
        val broker = brokerView.positionFor(symbol)?.quantity ?: Money.ZERO
        return strategySum.subtract(broker).setScale(Money.SCALE, Money.ROUNDING)
    }
}
