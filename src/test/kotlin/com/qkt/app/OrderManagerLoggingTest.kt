package com.qkt.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.broker.LogBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class OrderManagerLoggingTest {
    private val logger = LoggerFactory.getLogger(OrderManager::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun infoLines(): List<String> = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

    private fun newOrderManager(bus: EventBus): OrderManager {
        val clock = FixedClock(0L)
        return OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)
    }

    private fun submitMarket(om: OrderManager) {
        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
    }

    @Test
    fun `logs an info line when an order is filled`() {
        val bus = newBus()
        val om = newOrderManager(bus)
        submitMarket(om)

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
                strategyId = "alpha",
            ),
        )

        assertThat(infoLines()).anyMatch { it.contains("order filled") && it.contains("c1") && it.contains("alpha") }
    }

    @Test
    fun `logs an info line when an order is accepted`() {
        val bus = newBus()
        val om = newOrderManager(bus)
        submitMarket(om)

        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                strategyId = "alpha",
            ),
        )

        assertThat(infoLines()).anyMatch { it.contains("order accepted") && it.contains("c1") }
    }

    @Test
    fun `logs an info line when an order is partially filled`() {
        val bus = newBus()
        val om = newOrderManager(bus)
        submitMarket(om)

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
                strategyId = "alpha",
            ),
        )

        assertThat(infoLines()).anyMatch { it.contains("order partially filled") && it.contains("c1") }
    }

    @Test
    fun `logs an info line when an order is cancelled`() {
        val bus = newBus()
        val om = newOrderManager(bus)
        submitMarket(om)

        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                reason = "venue cancel",
                strategyId = "alpha",
            ),
        )

        assertThat(infoLines()).anyMatch { it.contains("order cancelled") && it.contains("c1") }
    }
}
