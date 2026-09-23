package com.qkt.app

import com.qkt.broker.CompositeBroker
import com.qkt.broker.MT5BrokerSimulator
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SymbolPattern
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * mt5-sim stands in for the MT5 attach path. A relative target is attached there by position modify
 * after the fill, so the simulator must not refuse its placeholder (live run 003 refused 8 of 10
 * stack legs while it was still sent); an absolute target still ships and is judged against the ask.
 * Every backtest routes the simulator through a [CompositeBroker], so each case runs routed too.
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
    fun `mt5-sim accepts a relative target placeholder inside the spread`() {
        assertThat(submit(tp = "4320.306").accepted).isTrue()
        assertThat(submit(tp = "4320.306", routed = true).accepted).isTrue()
    }

    @Test
    fun `mt5-sim refuses an absolute target inside the spread, routed or not`() {
        for (routed in listOf(false, true)) {
            val ack = submit(tp = "4320.306", routed = routed, absolute = true)

            assertThat(ack.accepted).isFalse()
            assertThat(ack.rejectReason).contains("For BUY orders, TP must be above entry price")
        }
    }

    private fun submit(
        tp: String,
        routed: Boolean = false,
        absolute: Boolean = false,
    ): com.qkt.broker.SubmitAck {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val prices = MarketPriceTracker()
        prices.update(quote)
        val sim = MT5BrokerSimulator(bus, clock, prices, registry)
        val broker =
            if (routed) {
                CompositeBroker(
                    listOf(SymbolPattern.exactSet(setOf("EXNESS:XAUUSD")) to sim),
                    bus = bus,
                )
            } else {
                sim
            }
        val om = OrderManager(broker, bus, prices, clock)
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
                takeProfitAst = if (absolute) ChildAt(NumLit(Money.of(tp))) else ChildBy(NumLit(BigDecimal("0.10"))),
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
