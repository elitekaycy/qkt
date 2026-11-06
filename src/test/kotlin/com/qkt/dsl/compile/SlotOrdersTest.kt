package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.Stop
import com.qkt.execution.OrderRequest
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `BUY`/`SELL`/`CLOSE` and `POSITION` on the event slot `p70 = KALSHI:KXS@front.p70`: orders land on the contract the
 * slot followed, an opposite order reduces what the book holds oldest first before opening the remainder, and the
 * book always has one sign. A plain stream (`gold`) is untouched.
 */
class SlotOrdersTest {
    private val held = HashMap<String, BigDecimal>()
    private var followed: String? = symbol(1, 2)
    private val positions =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? =
                held[symbol]?.let { Position(symbol, it, BigDecimal.ONE) }

            override fun allPositions(): Map<String, Position> =
                held.mapValues { (s, q) -> Position(s, q, BigDecimal.ONE) }
        }
    private val source =
        object : MarketSource {
            override val name = "slots"
            override val capabilities = emptySet<MarketSourceCapability>()

            override fun supports(symbol: String) = true

            override fun slotContractAt(
                symbol: String,
                atMs: Long,
            ): String? = followed.takeIf { symbol == "KALSHI:KXS@front.p70" }
        }
    private val streams =
        mapOf("p70" to "KALSHI:KXS@front.p70", "gold" to "EXNESS:XAUUSD").mapValues { (_, s) ->
            HubKey(s.substringBefore(':'), s.substringAfter(':'), "1m")
        }
    private val books = SlotBooks()
    private val exprs = ExprCompiler(slots = books)
    private val actions = ActionCompiler(exprs)
    private val tenThirty = SlotFamilyFixture.ms("2024-03-04T10:30:00Z")

    private fun ctx(at: Long = tenThirty): EvalContext {
        val one = BigDecimal.ONE
        return EvalContext(
            candle = Candle("KALSHI:KXS@front.p70", one, one, one, one, one, at - 60_000L, at),
            streams = streams,
            lets = emptyMap(),
            strategyContext =
                testStrategyContext(
                    strategyId = "s",
                    source = source,
                    positions = positions,
                    instruments = SlotFamilyFixture.registry(),
                ),
            evaluationTimeMs = at,
        )
    }

    private fun qty(n: Int) = ActionOpts(sizing = SizeQty(NumLit(BigDecimal(n))))

    private fun fire(
        action: ActionAst,
        at: Long = tenThirty,
    ): List<Signal> = actions.compile(action)(ctx(at))

    private fun position(at: Long = tenThirty): BigDecimal =
        (exprs.compile(PositionRef("p70")).evaluate(ctx(at)) as Value.Num).v

    @Test
    fun `a buy opens on the followed contract and the book reads its real position`() {
        assertThat(fire(Buy("p70", qty(10)))).containsExactly(Signal.Buy(symbol(1, 2), BigDecimal(10)))
        assertThat(position()).isEqualByComparingTo("0")

        held[symbol(1, 2)] = BigDecimal(10)
        assertThat(position()).isEqualByComparingTo("10")
    }

    @Test
    fun `an opposite order reduces held contracts first and opens only the remainder`() {
        fire(Buy("p70", qty(10)))
        held[symbol(1, 2)] = BigDecimal(10)
        followed = symbol(1, 3)

        assertThat(fire(Sell("p70", qty(14))))
            .containsExactly(Signal.Sell(symbol(1, 2), BigDecimal(10)), Signal.Sell(symbol(1, 3), BigDecimal(4)))
        held[symbol(1, 2)] = BigDecimal.ZERO
        held[symbol(1, 3)] = BigDecimal(-4)
        assertThat(position()).isEqualByComparingTo("-4")
        // A buy smaller than the short only reduces it.
        assertThat(fire(Buy("p70", qty(3)))).containsExactly(Signal.Buy(symbol(1, 3), BigDecimal(3)))
        assertThat(books.held(ctx().strategyContext, "p70", tenThirty)).containsExactly(symbol(1, 2), symbol(1, 3))
    }

    @Test
    fun `close flattens every held contract and cancels its working orders`() {
        fire(Buy("p70", qty(10)))
        followed = symbol(1, 3)
        fire(Buy("p70", qty(5)))
        held[symbol(1, 2)] = BigDecimal(10)
        held[symbol(1, 3)] = BigDecimal.ZERO
        followed = symbol(1, 4)
        fire(Sell("p70", qty(12)))
        held[symbol(1, 2)] = BigDecimal.ZERO
        held[symbol(1, 4)] = BigDecimal(-2)

        assertThat(fire(Close("p70"))).containsExactly(
            Signal.CancelPendingForSymbol(symbol(1, 2), closing = true),
            Signal.CancelPendingForSymbol(symbol(1, 3), closing = true),
            Signal.CancelPendingForSymbol(symbol(1, 4), closing = true),
            Signal.Buy(symbol(1, 4), BigDecimal(2)),
        )
        held[symbol(1, 4)] = BigDecimal(3)
        assertThat(fire(Close("p70")).last()).isEqualTo(Signal.Sell(symbol(1, 4), BigDecimal(3)))
    }

    @Test
    fun `a contract awaiting expiry blocks an opposite remainder, and expired empty contracts leave the book`() {
        fire(Buy("p70", qty(10)))
        held[symbol(1, 2)] = BigDecimal(10)
        val afterClose = SlotFamilyFixture.closeMs(1) + 30_000L
        followed = symbol(2, 2)

        assertThat(fire(Sell("p70", qty(4)), afterClose).single())
            .isEqualTo(
                Signal.Suppressed("KALSHI:KXS@front.p70", "p70 holds the other side in contracts awaiting expiry"),
            )
        assertThat(fire(Close("p70"), afterClose))
            .containsExactly(Signal.CancelPendingForSymbol(symbol(1, 2), closing = true))
        assertThat(position(afterClose)).isEqualByComparingTo("10")

        held[symbol(1, 2)] = BigDecimal.ZERO
        assertThat(books.held(ctx().strategyContext, "p70", afterClose)).isEmpty()
        assertThat(fire(Buy("p70", qty(1)), afterClose)).containsExactly(Signal.Buy(symbol(2, 2), BigDecimal.ONE))
    }

    @Test
    fun `no followed contract or one that stopped trading suppresses the order`() {
        followed = null
        val none = fire(Buy("p70", qty(1))).single() as Signal.Suppressed
        assertThat(none.reason).isEqualTo("p70 follows no contract yet")

        followed = symbol(1, 2)
        val reason = (fire(Buy("p70", qty(1)), SlotFamilyFixture.closeMs(1)).single() as Signal.Suppressed).reason
        assertThat(reason).isEqualTo("p70 follows ${symbol(1, 2)}, which stopped trading")
        assertThat((fire(Buy("p70", qty(0))).single() as Signal.Suppressed).reason).contains("not a positive quantity")
    }

    @Test
    fun `market and limit submits translate, other order types are refused`() {
        val market = fire(Buy("p70", qty(2).copy(tif = Ioc))).single() as Signal.Submit
        assertThat((market.request as OrderRequest.Market).symbol).isEqualTo(symbol(1, 2))

        val limitOpts = qty(2).copy(orderType = Limit(NumLit(BigDecimal("0.6"))))
        val limit = fire(Sell("p70", limitOpts)).single() as Signal.Submit
        assertThat((limit.request as OrderRequest.Limit).symbol).isEqualTo(symbol(1, 2))
        assertThat(limit.request.quantity).isEqualByComparingTo("2")

        val stop = fire(Buy("p70", qty(2).copy(orderType = Stop(NumLit(BigDecimal("0.8")))))).single()
        assertThat((stop as Signal.Suppressed).reason)
            .isEqualTo("p70 is an event slot: it takes market and limit orders, not Stop")
    }

    @Test
    fun `a plain stream trades and closes as before`() {
        assertThat(fire(Buy("gold", qty(1)))).containsExactly(Signal.Buy("EXNESS:XAUUSD", BigDecimal.ONE))
        assertThat(fire(Close("gold"))).containsExactly(Signal.CancelPendingForSymbol("EXNESS:XAUUSD", closing = true))
        held["EXNESS:XAUUSD"] = BigDecimal(2)
        assertThat((exprs.compile(PositionRef("gold")).evaluate(ctx()) as Value.Num).v).isEqualByComparingTo("2")
    }

    @Test
    fun `opening a contract already in the book keeps its place`() {
        books.opened("s", "p70", "A")
        books.opened("s", "p70", "B")
        books.opened("s", "p70", "A")

        assertThat(books.held(ctx().strategyContext, "p70", tenThirty)).containsExactly("A", "B")
    }
}
