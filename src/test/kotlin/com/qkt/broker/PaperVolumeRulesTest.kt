package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Paper applies the instrument's volume rules as the live venues and `mt5-sim` do: round down to the
 * step, refuse below `volumeMin` and above `volumeMax`.
 */
class PaperVolumeRulesTest {
    private fun registry(
        volumeMin: String = "0.1",
        volumeMax: String? = "5",
    ): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? =
                if (qktSymbol != "XAUUSD") {
                    null
                } else {
                    InstrumentMeta(
                        qktSymbol = qktSymbol,
                        contractSize = BigDecimal.ONE,
                        volumeStep = BigDecimal("0.1"),
                        volumeMin = BigDecimal(volumeMin),
                        volumeMax = volumeMax?.let(::BigDecimal),
                        pointSize = BigDecimal("0.01"),
                        digits = 2,
                        tradeStopsLevelPoints = 0,
                    )
                }
        }

    private fun market(
        quantity: String,
        symbol: String = "XAUUSD",
    ) = OrderRequest.Market(
        id = "o",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of(quantity),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `volume above the venue maximum is refused with the venue reason`() {
        val outcome = PaperVolumeRules(registry()).apply(market("5.19"))
        assertThat(outcome).isEqualTo(
            PaperVolumeRules.Outcome.Refused("quantized volume 5.1 above venue volumeMax 5 for XAUUSD"),
        )
    }

    @Test
    fun `volume rounding down onto the maximum is accepted`() {
        val outcome = PaperVolumeRules(registry()).apply(market("5.09")) as PaperVolumeRules.Outcome.Sized
        assertThat(outcome.request.quantity).isEqualByComparingTo("5.0")
    }

    @Test
    fun `volume below the venue minimum is refused with the venue reason`() {
        val outcome = PaperVolumeRules(registry(volumeMin = "0.2")).apply(market("0.19"))
        assertThat(outcome).isEqualTo(
            PaperVolumeRules.Outcome.Refused("quantized volume 0.1 below venue volumeMin 0.2 for XAUUSD"),
        )
    }

    @Test
    fun `no maximum and an unknown symbol both pass the order through`() {
        val unbounded = PaperVolumeRules(registry(volumeMax = null)).apply(market("999.95"))
        assertThat((unbounded as PaperVolumeRules.Outcome.Sized).request.quantity).isEqualByComparingTo("999.9")
        val unknown = PaperVolumeRules(registry()).apply(market("999.95", symbol = "EURUSD"))
        assertThat((unknown as PaperVolumeRules.Outcome.Sized).request.quantity).isEqualByComparingTo("999.95")
    }

    @Test
    fun `the paper broker rejects an order above the venue maximum without a fill`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("2000"))
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val rejections = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { rejections.add(it) }
        val broker = PaperBroker(bus, FixedClock(0L), tracker, registry())

        val ack = broker.submit(market("6"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isEqualTo("quantized volume 6.0 above venue volumeMax 5 for XAUUSD")
        assertThat(rejections.single().reason).isEqualTo(ack.rejectReason)
        assertThat(fills).isEmpty()
    }
}
