package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerEquityMonitorTest {
    @Test
    fun `stale broker equity is cleared until a fresh sample recovers`() {
        val clock = FixedClock(0L)
        val answers = listOf(BigDecimal("10000"), null, null, null, null, BigDecimal("10025"))
        var next = 0
        val broker = broker { answers[next++] }
        val equity = AtomicReference<BigDecimal?>(null)
        val alerts = mutableListOf<Pair<Int, Long?>>()
        val monitor =
            BrokerEquityMonitor(broker, clock, equity, 15_000L) { failures, age ->
                alerts.add(failures to age)
            }

        monitor.tick()
        repeat(4) { failure ->
            clock.time = (failure + 1) * 5_000L
            monitor.tick()
            if (failure < 2) {
                assertThat(equity.get()).isEqualByComparingTo("10000")
            } else {
                assertThat(equity.get()).isNull()
            }
        }

        assertThat(alerts).containsExactly(3 to 15_000L)
        clock.time = 25_000L
        monitor.tick()
        assertThat(equity.get()).isEqualByComparingTo("10025")
    }

    @Test
    fun `startup failures keep retrying and alert when no sample exists`() {
        val clock = FixedClock(0L)
        val answers = listOf<BigDecimal?>(null, null, null, BigDecimal("9000"))
        var next = 0
        val equity = AtomicReference<BigDecimal?>(null)
        val alerts = mutableListOf<Pair<Int, Long?>>()
        val monitor =
            BrokerEquityMonitor(broker { answers[next++] }, clock, equity, 15_000L) { failures, age ->
                alerts.add(failures to age)
            }

        repeat(3) { monitor.tick() }
        assertThat(alerts).containsExactly(3 to null)
        assertThat(equity.get()).isNull()

        monitor.tick()
        assertThat(equity.get()).isEqualByComparingTo("9000")
    }

    private fun broker(equity: () -> BigDecimal?): Broker =
        object : Broker {
            override val name = "equity-test"
            override val capabilities: Set<OrderTypeCapability> = emptySet()
            override val supportsAccountEquity = true

            override fun submit(request: OrderRequest): SubmitAck = error("unused")

            override fun cancel(orderId: String) = Unit

            override fun accountEquity(): BigDecimal? = equity()
        }
}
