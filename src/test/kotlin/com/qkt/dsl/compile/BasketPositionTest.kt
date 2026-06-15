package com.qkt.dsl.compile

import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.PositionRef
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #457 Task B9 — `POSITION.<basket>` reports unit-normalized direction across its
 * constituents (+1 all long, -1 all short, 0 flat or mixed), and `CLOSE <basket>` fans out
 * to one close per constituent.
 */
class BasketPositionTest {
    private val audSym = "EXNESS:AUDUSD"
    private val nzdSym = "EXNESS:NZDUSD"
    private val streams =
        mapOf(
            "aud" to HubKey("EXNESS", "AUDUSD", "1h"),
            "nzd" to HubKey("EXNESS", "NZDUSD", "1h"),
            "antipodean" to HubKey("BASKET", "ANTIPODEAN", "1h"),
        )
    private val baskets = mapOf("antipodean" to listOf("aud", "nzd"))

    /** A position view holding [audQty] on AUDUSD and [nzdQty] on NZDUSD (0 means flat). */
    private fun positions(
        audQty: String,
        nzdQty: String,
    ): StrategyPositionView =
        object : StrategyPositionView {
            private val held =
                buildMap {
                    if (BigDecimal(audQty).signum() != 0) {
                        put(audSym, Position(audSym, BigDecimal(audQty), BigDecimal("0.5")))
                    }
                    if (BigDecimal(nzdQty).signum() != 0) {
                        put(nzdSym, Position(nzdSym, BigDecimal(nzdQty), BigDecimal("0.6")))
                    }
                }

            override fun positionFor(symbol: String): Position? = held[symbol]

            override fun allPositions(): Map<String, Position> = held
        }

    private fun ec(view: StrategyPositionView): EvalContext =
        EvalContext(
            candle =
                Candle(
                    audSym,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    0L,
                    1L,
                ),
            streams = streams,
            lets = emptyMap(),
            strategyContext = testStrategyContext(positions = view),
        )

    private fun positionSign(view: StrategyPositionView): BigDecimal {
        val expr = ExprCompiler(baskets = baskets).compile(PositionRef("antipodean"))
        return (expr.evaluate(ec(view)) as Value.Num).v
    }

    @Test
    fun `basket position is plus one when all constituents are long`() {
        assertThat(positionSign(positions(audQty = "0.1", nzdQty = "0.2"))).isEqualByComparingTo("1")
    }

    @Test
    fun `basket position is minus one when all constituents are short`() {
        assertThat(positionSign(positions(audQty = "-0.1", nzdQty = "-0.2"))).isEqualByComparingTo("-1")
    }

    @Test
    fun `basket position is zero when flat`() {
        assertThat(positionSign(positions(audQty = "0", nzdQty = "0"))).isEqualByComparingTo("0")
    }

    @Test
    fun `basket position is zero when constituents disagree on side`() {
        assertThat(positionSign(positions(audQty = "0.1", nzdQty = "-0.2"))).isEqualByComparingTo("0")
    }

    @Test
    fun `CLOSE basket fans out a cancel and close per constituent`() {
        val action = ActionCompiler(ExprCompiler(), baskets = baskets).compile(Close("antipodean"))
        val signals = action(ec(positions(audQty = "0.1", nzdQty = "0.2")))
        // Per constituent, in order: cancel working orders, then net-close (Sell, since long).
        assertThat(signals.filterIsInstance<Signal.CancelPendingForSymbol>().map { it.symbol })
            .containsExactly(audSym, nzdSym)
        val sells = signals.filterIsInstance<Signal.Sell>()
        assertThat(sells.map { it.symbol }).containsExactly(audSym, nzdSym)
        assertThat(sells.map { it.size }).containsExactly(BigDecimal("0.1"), BigDecimal("0.2"))
    }
}
