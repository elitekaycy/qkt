package com.qkt.risk.book

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EngineBookStateSourceTest {
    private fun fill(
        s: String,
        sym: String,
        side: Side,
        qty: String,
        px: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = "c-$s-$sym",
        brokerOrderId = "b-$s-$sym",
        symbol = sym,
        side = side,
        price = BigDecimal(px),
        quantity = BigDecimal(qty),
        strategyId = s,
    )

    @Test
    fun `gross and net exposure from two hedging strategies`() {
        val prices = MarketPriceTracker()
        prices.update("X", BigDecimal("100"))
        val positions = StrategyPositionTracker()
        positions.applyFill(fill("a", "X", Side.BUY, "1", "100"))
        positions.applyFill(fill("b", "X", Side.SELL, "1", "100"))
        val strategyPnL = StrategyPnL(positions, prices)
        val pnl = PnLCalculator(PositionTracker(), prices)

        val source =
            EngineBookStateSource(
                strategyIds = listOf("a", "b"),
                pnl = pnl,
                strategyPnL = strategyPnL,
                positions = positions,
                prices = prices,
                instruments = NoopInstrumentRegistry,
                startingBalance = BigDecimal("10000"),
            )

        val snap = source.sample(1L)
        assertThat(snap.exposure.gross).isEqualByComparingTo("200")
        assertThat(snap.exposure.net).isEqualByComparingTo("0")
        assertThat(snap.bookEquity).isEqualByComparingTo("10000")
        assertThat(snap.perStrategyPnl.keys).containsExactlyInAnyOrder("a", "b")
    }
}
