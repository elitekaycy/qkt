package com.qkt.connector.mt5

import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.position
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A `gold` strategy's broker starting over positions it left open, and its close-by-ticket guards. */
class MT5BrokerStartupTest {
    private val gateway = Mt5RoutedGateway()
    private val clock = FixedClock(time = 1_800_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val rejected = mutableListOf<BrokerEvent.OrderRejected>()
    private val profile =
        MT5DefaultProfiles.exness.copy(
            gatewayUrl = gateway.url,
            retryAttempts = 0,
            httpTimeoutMs = 2_000L,
            pollIntervalMs = 3_600_000L,
            tickPollIntervalMs = 3_600_000L,
            expectedAccountLogin = 7L,
            instrumentOverrides =
                mapOf(
                    "EXNESS:XAUUSD" to
                        InstrumentSpec(
                            minVolume = BigDecimal("0.10"),
                            volumeStep = BigDecimal("0.01"),
                            pointSize = BigDecimal("0.01"),
                            digits = 2,
                            tradeStopsLevelPoints = 0,
                        ),
                ),
        )
    private var broker: MT5Broker? = null

    init {
        bus.subscribe<BrokerEvent.OrderRejected> { rejected.add(it) }
        gateway.on("/account", """{"login":7,"balance":1000,"equity":1000,"currency":"USD","margin_mode":0}""")
        gateway.on("/orders", "[]")
        gateway.on(
            "/get_positions",
            "[" + position(9001, "0.10", comment = "dsl-gold--3") + "," +
                position(9002, "0.10", comment = "dsl-gold-v2--1") + "]",
        )
    }

    @AfterEach
    fun stop() {
        broker?.shutdown()
        gateway.close()
    }

    private fun start() = MT5Broker(profile, bus, clock, strategyName = "gold").also { broker = it }

    private fun close(
        ticket: String,
        quantity: String,
    ) = OrderRequest.Market(
        "exit-1",
        "EXNESS:XAUUSD",
        Side.SELL,
        BigDecimal(quantity),
        TimeInForce.GTC,
        1L,
        "gold",
        closesTicket = ticket,
    )

    @Test
    fun `a position named for this strategy is seeded to it, one named for a longer name is not`() {
        val attributions = start().ticketAttributions()

        assertThat(attributions).containsEntry("9001", "gold")
        assertThat(attributions).doesNotContainKey("9002")
    }

    @Test
    fun `the verified account's netting mode is known without asking again`() {
        val broker = start()
        val asked = gateway.calls("/account").size

        assertThat(broker.positionAccountingMode("EXNESS:XAUUSD")).isEqualTo(PositionAccountingMode.NETTING)
        assertThat(gateway.calls("/account")).hasSize(asked)
        assertThat(broker.supports("ANY:THING")).isTrue()
    }

    @Test
    fun `a close naming a ticket that is not a number is rejected`() {
        val ack = start().submit(close("abc", "0.10"))

        assertThat(ack.accepted).isFalse()
        assertThat(rejected.single().reason).isEqualTo("closesTicket is not a valid ticket: abc")
    }

    @Test
    fun `a close below the venue's minimum volume is rejected before it is sent`() {
        val ack = start().submit(close("9001", "0.05"))

        assertThat(ack.accepted).isFalse()
        assertThat(rejected.single().reason).startsWith("quantized volume below venue volumeMin")
        assertThat(gateway.calls("/close_position")).isEmpty()
    }
}
