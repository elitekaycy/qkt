package com.qkt.dsl.compile

import com.qkt.instrument.EventTerms
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

/**
 * The contracts each event slot of a strategy opened, oldest first, keyed by strategy and slot alias: the slot's book.
 * The book holds membership only; what it holds of each contract is the strategy's real position on it, so a contract
 * settled by its expiry or closed by another rule reads 0 without the book being told. A contract leaves the book once
 * it has expired with nothing held. `POSITION.<slot>` is the book's signed total ([total]); `BUY`/`SELL`/`CLOSE` on a
 * slot work through it ([SlotOrders]).
 */
class SlotBooks {
    private val books = HashMap<Pair<String, String>, LinkedHashSet<String>>()

    /** The contracts [alias] of strategy [ctx] holds or has held, oldest first; expired ones holding nothing pruned. */
    @Synchronized
    fun held(
        ctx: StrategyContext,
        alias: String,
        nowMs: Long,
    ): List<String> {
        val book = books[ctx.strategyId to alias] ?: return emptyList()
        book.removeAll { contract ->
            val terms = ctx.instruments.lookup(contract)?.derivative as? EventTerms
            terms != null && terms.expiryMs <= nowMs && quantity(ctx, contract).signum() == 0
        }
        return book.toList()
    }

    /** Records that [alias] of strategy [strategyId] opened [contract]; one already in the book keeps its place. */
    @Synchronized
    fun opened(
        strategyId: String,
        alias: String,
        contract: String,
    ) {
        books.getOrPut(strategyId to alias) { LinkedHashSet() }.add(contract)
    }

    /** The signed total [alias] holds across its book: positive long YES, negative long NO, 0 when flat. */
    fun total(
        ctx: StrategyContext,
        alias: String,
        nowMs: Long,
    ): BigDecimal = held(ctx, alias, nowMs).fold(BigDecimal.ZERO) { acc, c -> acc.add(quantity(ctx, c)) }

    /** What strategy [ctx] holds of [contract], signed. */
    fun quantity(
        ctx: StrategyContext,
        contract: String,
    ): BigDecimal = ctx.positions.positionFor(contract)?.quantity ?: BigDecimal.ZERO
}
