package com.qkt.app

import com.qkt.broker.MT5BrokerSimulator
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The live scale-burst trace, replayed against mt5-sim: a stack leg whose `TAKE PROFIT BY 0.10`
 * placeholder sat above the mid but 0.093 below the ask was refused by the MT5 gateway, while the
 * simulator opened it. The simulator stands in for that venue, so it must refuse it too.
 */
class OrderManagerMt5SimSubmittedProtectionTest {
    private val quote =
        Tick(
            "EXNESS:XAUUSD",
            Money.of("4320.269"),
            1L,
            bid = Money.of("4320.139"),
            ask = Money.of("4320.399"),
        )

    @Test
    fun `mt5-sim refuses a relative target placeholder inside the spread as the venue does`() {
        val ack = submit(tp = "4320.306")

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("For BUY orders, TP must be above entry price")
    }

    @Test
    fun `mt5-sim accepts a relative target placeholder beyond the ask`() {
        val ack = submit(tp = "4320.499")

        assertThat(ack.accepted).isTrue()
    }

    private fun submit(tp: String): com.qkt.broker.SubmitAck {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val prices = MarketPriceTracker()
        prices.update(quote)
        val sim = MT5BrokerSimulator(bus, clock, prices, registry)
        val om = OrderManager(sim, bus, prices, clock)
        return om.submit(
            OrderRequest.Bracket(
                id = "stack-tier0",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.05"),
                entry =
                    OrderRequest.Market(
                        id = "stack-tier0-entry",
                        symbol = "EXNESS:XAUUSD",
                        side = Side.BUY,
                        quantity = Money.of("0.05"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                    ),
                takeProfit = Money.of(tp),
                stopLoss = StopLossSpec.Fixed(Money.of("4305.206")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                takeProfitAst = ChildBy(NumLit(BigDecimal("0.10"))),
                stopLossAst = ChildBy(NumLit(BigDecimal("15.00"))),
            ),
        )
    }

    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? =
                if (qktSymbol != "EXNESS:XAUUSD") {
                    null
                } else {
                    InstrumentMeta(
                        qktSymbol = "EXNESS:XAUUSD",
                        contractSize = BigDecimal("100"),
                        volumeStep = BigDecimal("0.01"),
                        volumeMin = BigDecimal("0.01"),
                        volumeMax = null,
                        pointSize = BigDecimal("0.001"),
                        digits = 3,
                        tradeStopsLevelPoints = 0,
                    )
                }
        }
}
