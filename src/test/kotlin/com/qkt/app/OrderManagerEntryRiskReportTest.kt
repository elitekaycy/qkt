package com.qkt.app

import com.qkt.app.OrderManagerFixtures.bracket
import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.LogBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerEntryRiskReportTest {
    private fun registry(meta: InstrumentMeta): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == meta.qktSymbol) meta else null
        }

    @Test
    fun `risk is not recorded when tracking is off, so the live map does not leak`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("EURUSD", Money.of("1.10")) }
        val om = OrderManager(broker, bus, tracker, clock, trackRisk = false)

        om.submit(bracket("b1", "e1"))

        assertThat(om.riskUsdFor("e1")).isNull()
        assertThat(om.riskUsdFor("b1")).isNull()
    }

    @Test
    fun `risk is recorded for the backtest report when tracking is on`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("EURUSD", Money.of("1.10")) }
        val om = OrderManager(broker, bus, tracker, clock, trackRisk = true)

        om.submit(bracket("b1", "e1"))

        // risk = |entry 1.10 - stop 1.09| * qty 1 = 0.01
        assertThat(om.riskUsdFor("e1")).isEqualByComparingTo("0.01")
    }

    @Test
    fun `risk report scales price distance by instrument contract size`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("BACKTEST:XAUUSD", Money.of("2010")) }
        val om =
            OrderManager(
                broker,
                bus,
                tracker,
                clock,
                instruments =
                    registry(
                        InstrumentMeta(
                            qktSymbol = "BACKTEST:XAUUSD",
                            contractSize = BigDecimal("100"),
                            volumeStep = BigDecimal("0.01"),
                            volumeMin = BigDecimal("0.01"),
                            volumeMax = BigDecimal("200"),
                            pointSize = BigDecimal("0.001"),
                            digits = 3,
                            tradeStopsLevelPoints = 0,
                        ),
                    ),
                trackRisk = true,
            )

        om.submit(bracket("b1", "e1", symbol = "BACKTEST:XAUUSD", entry = "2010", stop = "2000"))

        // risk = |entry 2010 - stop 2000| * qty 1 * contractSize 100 = 1000.
        assertThat(om.riskUsdFor("e1")).isEqualByComparingTo("1000")
        val protection = om.protectionFor("e1")
        assertThat(protection?.stopLoss).isEqualByComparingTo("2000")
        assertThat(protection?.takeProfit).isEqualByComparingTo("2030")
    }

    @Test
    fun `entry risk report reanchors relative protection to the exact fill`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("BACKTEST:XAUUSD", Money.of("2010")) }
        val om =
            OrderManager(
                broker,
                bus,
                tracker,
                clock,
                instruments =
                    registry(
                        InstrumentMeta(
                            qktSymbol = "BACKTEST:XAUUSD",
                            contractSize = BigDecimal("100"),
                            volumeStep = BigDecimal("0.01"),
                            volumeMin = BigDecimal("0.01"),
                            volumeMax = BigDecimal("200"),
                            pointSize = BigDecimal("0.001"),
                            digits = 3,
                            tradeStopsLevelPoints = 0,
                        ),
                    ),
                trackRisk = true,
            )
        val request =
            bracket("b1", "e1", symbol = "BACKTEST:XAUUSD", entry = "2010", stop = "2000").copy(
                quantity = BigDecimal("0.05"),
                entry =
                    OrderRequest.Market(
                        id = "e1",
                        symbol = "BACKTEST:XAUUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.05"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                    ),
                stopLossAst = ChildBy(NumLit(BigDecimal("10"))),
                takeProfitAst = ChildRr(NumLit(BigDecimal("2"))),
            )
        om.submit(request)

        val report =
            om.entryRiskForFill(
                clientOrderId = "e1",
                quantity = BigDecimal("0.05"),
                fillPrice = BigDecimal("2010.31"),
                symbol = "BACKTEST:XAUUSD",
            )

        assertThat(report?.riskUsd).isEqualByComparingTo("50")
        assertThat(report?.protection?.stopLoss).isEqualByComparingTo("2000.31")
        assertThat(report?.protection?.takeProfit).isEqualByComparingTo("2030.31")
        assertThat(om.entryRiskForFill("e1", BigDecimal("0.05"), BigDecimal("2010.31"), "BACKTEST:XAUUSD"))
            .isNull()
    }
}
