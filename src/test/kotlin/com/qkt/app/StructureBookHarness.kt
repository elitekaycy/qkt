package com.qkt.app

import com.qkt.common.Side
import com.qkt.events.StructureEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal

/** A [StructureBook] for strategy `st` over contract size 1: a leg of 0.1 contract moves 0.1 per unit of premium. */
internal abstract class StructureBookHarness {
    protected val published = mutableListOf<StructureEvent>()
    protected val book = StructureBook("st", StructureFixtures.registry, MarketPriceTracker()) { published += it }
    protected val shortPut = StructureFixtures.market("s", StructureFixtures.P81, Side.SELL)
    protected val longPut = StructureFixtures.market("l", StructureFixtures.P78, Side.BUY)

    protected fun openSpread(alias: String = "ps") {
        book.accept(
            Signal.SubmitGroup("$alias-1", alias, listOf(shortPut, longPut).map { it.copy(id = "$alias-${it.id}") }),
        )
        book.filled("$alias-s", BigDecimal("0.1"), BigDecimal("646"))
        book.filled("$alias-l", BigDecimal("0.1"), BigDecimal("219"))
    }

    protected fun close(
        alias: String,
        vararg legs: Pair<String, String>,
    ) = book.accept(
        Signal.SubmitGroup(
            "close-$alias",
            alias,
            legs.map { (id, symbol) ->
                StructureFixtures.market(id, symbol, if (symbol == StructureFixtures.P81) Side.BUY else Side.SELL)
            },
            closes = "$alias-1",
        ),
    )
}
