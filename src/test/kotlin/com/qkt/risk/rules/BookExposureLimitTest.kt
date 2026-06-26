package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.book.BookLimits
import com.qkt.risk.book.BookRiskConfig
import com.qkt.risk.book.BookRiskController
import com.qkt.risk.book.BookSnapshot
import com.qkt.risk.book.Exposure
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookExposureLimitTest {
    private val prices = MarketPriceTracker().apply { update("X", BigDecimal("100")) }
    private val positions = PositionTracker()

    private fun controllerWithGross(gross: String): BookRiskController {
        val c =
            BookRiskController(
                config = BookRiskConfig(limits = BookLimits(maxGrossExposure = BigDecimal("3"))),
                capital = BigDecimal("10000"),
            )
        c.onSample(
            BookSnapshot(
                timestampMs = 1L,
                bookEquity = BigDecimal("10000"),
                exposure = Exposure(BigDecimal(gross), BigDecimal(gross), mapOf("X" to BigDecimal(gross))),
                perStrategyPnl = emptyMap(),
            ),
        )
        return c
    }

    private fun buy(qty: String) =
        OrderRequest.Market(
            id = "o1",
            symbol = "X",
            side = Side.BUY,
            quantity = BigDecimal(qty),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    private fun buy(
        symbol: String,
        qty: String,
    ) = OrderRequest.Market(
        id = "o1",
        symbol = symbol,
        side = Side.BUY,
        quantity = BigDecimal(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = 1L,
    )

    @Test
    fun `rejects a risk-increasing order that breaches the book gross cap`() {
        val rule = BookExposureLimit(controllerWithGross("29000"), prices, NoopInstrumentRegistry)
        val d = rule.evaluate(buy("20"), positions) // 29000 + 20*100 = 31000 > 30000
        assertThat(d).isInstanceOf(Decision.Reject::class.java)
        assertThat((d as Decision.Reject).reason).contains("book gross")
    }

    @Test
    fun `approves an order under the cap`() {
        val rule = BookExposureLimit(controllerWithGross("29000"), prices, NoopInstrumentRegistry)
        assertThat(rule.evaluate(buy("5"), positions)).isEqualTo(Decision.Approve) // 29500 <= 30000
    }

    @Test
    fun `uses account-currency exposure when checking FX book caps`() {
        val fxPrices = MarketPriceTracker().apply { update("BACKTEST:USDJPY", BigDecimal("150")) }
        val rule = BookExposureLimit(controllerWithGross("29000"), fxPrices, NoopInstrumentRegistry)

        assertThat(rule.evaluate(buy("BACKTEST:USDJPY", "500"), positions)).isEqualTo(Decision.Approve)
        val decision = rule.evaluate(buy("BACKTEST:USDJPY", "1500"), positions)

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("book gross")
    }
}
