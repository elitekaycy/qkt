package com.qkt.app

import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Contract size 1: a leg of 0.1 contract moves 0.1 per unit of premium. */
class StructureBookTest {
    private val book = StructureBook(StructureFixtures.registry, MarketPriceTracker())
    private val shortPut = StructureFixtures.market("s", StructureFixtures.P81, Side.SELL)
    private val longPut = StructureFixtures.market("l", StructureFixtures.P78, Side.BUY)

    private fun openSpread(alias: String = "ps") {
        book.accept(
            Signal.SubmitGroup("$alias-1", alias, listOf(shortPut, longPut).map { it.copy(id = "$alias-${it.id}") }),
        )
        book.filled("$alias-s", BigDecimal("0.1"), BigDecimal("646"))
        book.filled("$alias-l", BigDecimal("0.1"), BigDecimal("219"))
    }

    private fun close(
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

    @Test
    fun `a structure is pending until every leg fills, then open with signed legs`() {
        book.accept(Signal.SubmitGroup("ps-1", "ps", listOf(shortPut, longPut)))
        book.filled("s", BigDecimal("0.1"), BigDecimal("646"))
        assertThat(book.live("ps")?.state).isEqualTo(StructureState.PENDING)

        book.filled("l", BigDecimal("0.1"), BigDecimal("219"))

        val ps = requireNotNull(book.live("ps"))
        assertThat(ps.state).isEqualTo(StructureState.OPEN)
        assertThat(ps.size).isEqualByComparingTo("0.1")
        assertThat(ps.legs.map { Triple(it.symbol, it.heldQuantity, it.entryPrice) }).containsExactly(
            Triple(StructureFixtures.P81, BigDecimal("-0.1"), BigDecimal("646")),
            Triple(StructureFixtures.P78, BigDecimal("0.1"), BigDecimal("219")),
        )
        assertThat(ps.legs.map { it.expiryMs }).containsOnly(StructureFixtures.OCT9)
    }

    @Test
    fun `closing fills realize premium P&L and the closed structure leaves the book`() {
        openSpread()
        close("ps", "c1" to StructureFixtures.P81, "c2" to StructureFixtures.P78)
        assertThat(book.live("ps")?.state).isEqualTo(StructureState.CLOSING)

        book.filled("c1", BigDecimal("0.1"), BigDecimal("300"))

        // Short sold at 646, bought back at 300: +0.1 x 346.
        val short = requireNotNull(book.live("ps")).legs.first()
        assertThat(short.realized).isEqualByComparingTo("34.6")
        assertThat(short.heldQuantity).isEqualByComparingTo("0")
        book.filled("c2", BigDecimal("0.1"), BigDecimal("100"))
        assertThat(book.live("ps")).isNull()
    }

    @Test
    fun `a close the venue rejects leaves the structure open, so it can be closed again`() {
        openSpread()
        close("ps", "c1" to StructureFixtures.P81)

        book.ended("c1")

        assertThat(book.live("ps")?.state).isEqualTo(StructureState.OPEN)
    }

    @Test
    fun `expiry settles each alias's legs on a shared contract by its own entry`() {
        openSpread("ps")
        book.accept(
            Signal.SubmitGroup("qs-1", "qs", listOf(StructureFixtures.market("q", StructureFixtures.P81, Side.BUY))),
        )
        book.filled("q", BigDecimal("0.1"), BigDecimal("600"))

        book.settled(StructureFixtures.P81, BigDecimal("1000"))

        // ps short from 646 pays 1000: -0.1 x 354. qs long from 600 receives 1000: +0.1 x 400.
        assertThat(requireNotNull(book.live("ps")).legs.first().realized).isEqualByComparingTo("-35.4")
        assertThat(book.live("qs")).isNull()
        book.settled(StructureFixtures.P78, BigDecimal.ZERO)
        assertThat(book.live("ps")).isNull()
    }

    @Test
    fun `a group refused by risk sent nothing and is forgotten, a refused close keeps the structure open`() {
        book.accept(Signal.SubmitGroup("ps-1", "ps", listOf(shortPut, longPut)))
        book.refused("s")
        assertThat(book.live("ps")).isNull()

        openSpread("qs")
        close("qs", "c1" to StructureFixtures.P81)
        book.refused("c1")

        assertThat(book.live("qs")?.state).isEqualTo(StructureState.OPEN)
    }

    @Test
    fun `a fill that is no structure's order closes legs held on the other side, oldest structure first`() {
        openSpread("ps")
        openSpread("qs")

        book.external(StructureFixtures.P81, Side.BUY, BigDecimal("0.15"), BigDecimal("700"))
        book.external(StructureFixtures.P78, Side.BUY, BigDecimal("0.1"), BigDecimal("300"))

        // ps's short is bought back whole at 700 (-0.1 x 54), qs's for the 0.05 left; a buy never closes a long.
        val ps = requireNotNull(book.live("ps")).legs
        assertThat(ps.first().heldQuantity).isEqualByComparingTo("0")
        assertThat(ps.first().realized).isEqualByComparingTo("-5.4")
        assertThat(requireNotNull(book.live("qs")).legs.first().heldQuantity).isEqualByComparingTo("-0.05")
        assertThat(ps.last().heldQuantity).isEqualByComparingTo("0.1")
    }

    @Test
    fun `a closing fill beyond what the leg still holds closes only what it holds`() {
        openSpread()
        book.external(StructureFixtures.P81, Side.BUY, BigDecimal("0.1"), BigDecimal("700"))
        close("ps", "c1" to StructureFixtures.P81)

        book.filled("c1", BigDecimal("0.1"), BigDecimal("710"))

        val short = requireNotNull(book.live("ps")).legs.first()
        assertThat(short.heldQuantity).isEqualByComparingTo("0")
        assertThat(short.realized).isEqualByComparingTo("-5.4")
    }

    @Test
    fun `a structure closed leg by leg from outside leaves the book`() {
        openSpread()

        book.external(StructureFixtures.P78, Side.SELL, BigDecimal("0.1"), BigDecimal("200"))
        book.external(StructureFixtures.P81, Side.BUY, BigDecimal("0.1"), BigDecimal("600"))

        assertThat(book.live("ps")).isNull()
    }
}
