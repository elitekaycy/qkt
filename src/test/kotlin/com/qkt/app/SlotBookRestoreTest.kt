package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.SlotBookOwner
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.Tick
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * After a restart, an event slot's book is rebuilt from the contracts of its family the strategy holds again, oldest
 * leg first; a contract of no slot's family, a flat one and a non-event symbol stay out of every book.
 */
class SlotBookRestoreTest {
    private val strategy =
        AstCompiler().compile(
            (
                Dsl.parse(
                    "STRATEGY t VERSION 1\nSYMBOLS\n    p = KALSHI:KXS@front.p70 EVERY 1m,\n" +
                        "    a = KALSHI:KXS@front.atm EVERY 1m,\n    g = EXNESS:XAUUSD EVERY 1m\n" +
                        "RULES\n    WHEN p.close > 0 THEN LOG \"x\"\n",
                ) as ParseResult.Success
            ).value,
        )
    private val tracker = StrategyPositionTracker()
    private val tenThirty = SlotFamilyFixture.ms("2024-03-04T10:30:00Z")

    private fun hold(
        symbol: String,
        openedAt: Long,
    ) = tracker.addIndependentLeg(
        "s",
        PositionLeg("leg-$symbol", symbol, Side.BUY, BigDecimal.TEN, BigDecimal("0.5"), openedAt, LegRole.INDEPENDENT),
    )

    private val view =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? = tracker.positionFor("s", symbol)

            override fun allPositions(): Map<String, Position> = tracker.positionsFor("s")
        }

    @Test
    fun `held contracts of the family return to the first slot's book, oldest first`() {
        hold(symbol(1, 2), 200L)
        hold(symbol(1, 1), 100L)
        hold("EXNESS:XAUUSD", 50L)
        hold("KALSHI:KXO_H1_T1", 10L)
        val restored = listOf(symbol(1, 2), symbol(1, 1), "EXNESS:XAUUSD", "KALSHI:KXO_H1_T1", symbol(2, 3))

        restoreSlotBooks("s", strategy, restored, tracker, SlotFamilyFixture.registry())

        val books = (strategy as SlotBookOwner).slotBooks
        val ctx = testStrategyContext(strategyId = "s", positions = view, instruments = SlotFamilyFixture.registry())
        assertThat(books.held(ctx, "p", tenThirty)).containsExactly(symbol(1, 1), symbol(1, 2))
        assertThat(books.held(ctx, "a", tenThirty)).isEmpty()
        assertThat(books.total(ctx, "p", tenThirty)).isEqualByComparingTo("20")
    }

    @Test
    fun `a strategy without slot books is left alone`() {
        hold(symbol(1, 2), 200L)
        val plain =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }

        restoreSlotBooks("s", plain, listOf(symbol(1, 2)), tracker, SlotFamilyFixture.registry())

        assertThat(plain).isNotInstanceOf(SlotBookOwner::class.java)
    }
}
