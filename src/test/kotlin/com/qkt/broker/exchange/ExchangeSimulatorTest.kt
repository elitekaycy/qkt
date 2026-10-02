package com.qkt.broker.exchange

import com.qkt.accounting.CostKind
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExchangeSimulatorTest {
    private val f = ExchangeFixture()

    @Test
    fun `market fills move the root's slippage ticks against the order`() {
        f.tick(f.sep, "63000.0")

        f.sim.submit(f.market("b", Side.BUY, "0.01"))
        f.sim.submit(f.market("s", Side.SELL, "0.01"))

        val fills = f.only<BrokerEvent.OrderFilled>().associateBy { it.clientOrderId }
        assertThat(fills.getValue("b").price).isEqualByComparingTo("63000.2")
        assertThat(fills.getValue("s").price).isEqualByComparingTo("62999.8")
    }

    @Test
    fun `a triggered stop slips and a limit fills at its price`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.stop("stop", Side.SELL, "62900.0"))
        f.sim.submit(f.limit("limit", Side.BUY, "62950.0"))

        f.tick(f.sep, "62950.0")
        f.tick(f.sep, "62900.0")

        val fills = f.only<BrokerEvent.OrderFilled>().associateBy { it.clientOrderId }
        assertThat(fills.getValue("limit").price).isEqualByComparingTo("62950.0")
        assertThat(fills.getValue("stop").price).isEqualByComparingTo("62899.8")
    }

    @Test
    fun `a quantity above the venue maximum is rejected`() {
        f.tick(f.sep, "63000.0")

        val ack = f.sim.submit(f.market("big", Side.BUY, "100.001"))

        assertThat(ack.accepted).isFalse()
        assertThat(f.only<BrokerEvent.OrderRejected>().single().reason)
            .contains("100.001")
            .contains("volumeMax 100")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
    }

    @Test
    fun `levels off the tick grid are snapped so they never fill early`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.limit("bid", Side.BUY, "62950.05"))
        f.sim.submit(f.stop("protect", Side.SELL, "62900.05"))

        f.tick(f.sep, "62950.1")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
        f.tick(f.sep, "62950.0")
        f.tick(f.sep, "62900.1")
        assertThat(f.only<BrokerEvent.OrderFilled>().map { it.clientOrderId }).containsExactly("bid")
        f.tick(f.sep, "62900.0")

        assertThat(f.only<BrokerEvent.OrderFilled>().map { it.clientOrderId }).containsExactly("bid", "protect")
        assertThat(f.only<BrokerEvent.OrderRejected>()).isEmpty()
    }

    @Test
    fun `orders on a symbol that is not a dated or perpetual contract are rejected`() {
        val continuous = f.sim.submit(f.market("c", Side.BUY, "0.01", symbol = "BINANCE_UM:BTCUSDT@front"))
        val unknown = f.sim.submit(f.market("u", Side.BUY, "0.01", symbol = "EXNESS:XAUUSD"))

        assertThat(continuous.rejectReason).contains("not a dated or perpetual futures contract")
        assertThat(unknown.rejectReason).contains("no instrument metadata")
    }

    @Test
    fun `order shapes outside the declared capabilities are rejected`() {
        val touched =
            OrderRequest.IfTouched(
                "t",
                f.sep,
                Side.BUY,
                BigDecimal("0.01"),
                BigDecimal("62000.0"),
                TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = f.clock.time,
                strategyId = "s",
            )

        assertThat(f.sim.submit(touched).rejectReason).contains("does not accept IfTouched")
    }

    @Test
    fun `accepted and cancelled events reach the engine bus with the engine's id`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.limit("rest", Side.BUY, "62000.0"))

        f.sim.cancel("rest")

        assertThat(f.only<BrokerEvent.OrderAccepted>().single().clientOrderId).isEqualTo("rest")
        assertThat(f.only<BrokerEvent.OrderCancelled>().single().clientOrderId).isEqualTo("rest")
    }

    @Test
    fun `the simulator nets`() {
        assertThat(f.sim.positionAccountingMode(f.sep)).isEqualTo(com.qkt.broker.PositionAccountingMode.NETTING)
    }

    @Test
    fun `each fill carries the contract fee on its own price as a venue cost`() {
        val taker = ExchangeFixture(takerFeeRate = "0.0005")
        taker.tick(taker.sep, "63000.0")

        taker.sim.submit(taker.market("b", Side.BUY, "0.01"))

        val cost =
            taker
                .only<BrokerEvent.OrderFilled>()
                .single()
                .typedVenueCosts
                .single()
        assertThat(cost.kind).isEqualTo(CostKind.EXCHANGE_FEE)
        assertThat(cost.amount.currency).isEqualTo("USDT")
        // 0.01 x 63000.2 (after two ticks of slippage) x 0.0005
        assertThat(cost.amount.amount).isEqualByComparingTo("0.315001")
    }

    @Test
    fun `a fee-free contract adds no venue cost`() {
        f.tick(f.sep, "63000.0")

        f.sim.submit(f.market("b", Side.BUY, "0.01"))

        assertThat(f.only<BrokerEvent.OrderFilled>().single().typedVenueCosts).isEmpty()
    }
}
