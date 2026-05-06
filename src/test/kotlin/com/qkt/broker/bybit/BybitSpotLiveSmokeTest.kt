package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("e2e-live")
class BybitSpotLiveSmokeTest {
    @Test
    fun `submits a far-from-market limit on Bybit testnet then cancels`() {
        val key = System.getenv("BYBIT_API_KEY")
        val secret = System.getenv("BYBIT_API_SECRET")
        assumeTrue(key != null && secret != null, "BYBIT_API_KEY and BYBIT_API_SECRET required")

        val clock = SystemClock()
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val client = BybitClient(testnet = true)
        client.connect()
        try {
            val broker = BybitSpotBroker(client, bus, clock)

            val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
            val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
            val acceptLatch = CountDownLatch(1)
            val cancelLatch = CountDownLatch(1)
            bus.subscribe<BrokerEvent.OrderAccepted> { e ->
                accepts.add(e)
                acceptLatch.countDown()
            }
            bus.subscribe<BrokerEvent.OrderCancelled> { e ->
                cancels.add(e)
                cancelLatch.countDown()
            }

            val orderId = "qkt-smoke-${clock.now()}"
            val ack =
                broker.submit(
                    OrderRequest.Limit(
                        id = orderId,
                        symbol = "BYBIT_SPOT:BTCUSDT",
                        side = Side.BUY,
                        quantity = Money.of("0.001"),
                        limitPrice = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = clock.now(),
                    ),
                )
            assertThat(ack.accepted).isTrue()

            assertThat(acceptLatch.await(15, TimeUnit.SECONDS))
                .withFailMessage("did not receive OrderAccepted within 15s")
                .isTrue()

            broker.cancel(orderId)

            assertThat(cancelLatch.await(15, TimeUnit.SECONDS))
                .withFailMessage("did not receive OrderCancelled within 15s")
                .isTrue()
            assertThat(cancels.single().clientOrderId).isEqualTo(orderId)
        } finally {
            client.close()
        }
    }
}
