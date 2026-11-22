package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** One print reaching several resting orders fills them nearest the price the move started from (actions.md). */
class PaperBrokerTriggerSequenceTest {
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val fills = mutableListOf<String>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it.clientOrderId }
    }

    private fun broker(barReplayed: Boolean) =
        PaperBroker(bus, FixedClock(0L), MarketPriceTracker(), fillAtTriggerPrice = { barReplayed })

    private fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(id, "SYN", side, Money.of("1"), Money.of(price), TimeInForce.GTC, 0L)

    private fun stop(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Stop(id, "SYN", side, Money.of("1"), Money.of(price), TimeInForce.GTC, 0L)

    private fun tick(
        price: String,
        ts: Long,
        volume: String? = null,
    ) = Tick("SYN", Money.of(price), ts, volume = volume?.let(::BigDecimal))

    @Test
    fun `a gap tick fills the order nearest the previous tick first`() {
        val paper = broker(barReplayed = false)
        paper.onTick(tick("100.20", 1L))
        paper.submit(limit("buy-limit", Side.BUY, "99.00"))
        paper.submit(stop("sell-stop", Side.SELL, "99.50"))

        paper.onTick(tick("98.80", 2L))

        assertThat(fills).containsExactly("sell-stop", "buy-limit")
    }

    @Test
    fun `a bar opening beyond both orders fills the one nearest the open first`() {
        val paper = broker(barReplayed = true)
        paper.onTick(tick("100.20", 1L, volume = "1"))
        paper.submit(stop("sell-stop", Side.SELL, "99.50"))
        paper.submit(limit("buy-limit", Side.BUY, "99.00"))

        paper.onTick(tick("98.80", 2L))

        assertThat(fills).containsExactly("buy-limit", "sell-stop")
    }

    @Test
    fun `a bar's later print measures from that bar's open`() {
        val paper = broker(barReplayed = true)
        paper.onTick(tick("100.20", 1L, volume = "1"))
        paper.onTick(tick("100.10", 2L))
        paper.submit(stop("sell-stop", Side.SELL, "99.90"))
        paper.submit(limit("buy-limit", Side.BUY, "100.05"))

        paper.onTick(tick("99.80", 3L))

        assertThat(fills).containsExactly("buy-limit", "sell-stop")
    }

    @Test
    fun `orders at the same distance fill in the order they were submitted`() {
        val paper = broker(barReplayed = false)
        paper.onTick(tick("100.00", 1L))
        paper.submit(stop("first", Side.SELL, "99.50"))
        paper.submit(limit("second", Side.BUY, "99.50"))

        paper.onTick(tick("99.00", 2L))

        assertThat(fills).containsExactly("first", "second")
    }

    @Test
    fun `a cancelled or expired order leaves the sequence`() {
        val paper = broker(barReplayed = false)
        paper.onTick(tick("100.00", 1L))
        paper.submit(stop("gone", Side.SELL, "99.50"))
        paper.submit(stop("dated", Side.SELL, "99.40").copy(timeInForce = TimeInForce.GTD, expiresAt = 2L))
        paper.submit(stop("kept", Side.SELL, "99.60"))
        paper.cancel("gone")

        paper.onTick(tick("99.00", 2L))

        assertThat(fills).containsExactly("kept")
    }

    @Test
    fun `stop-limit and if-touched orders are sequenced by their trigger prices`() {
        val paper = broker(barReplayed = false)
        paper.onTick(tick("100.00", 1L))
        paper.submit(
            OrderRequest.IfTouched(
                id = "touched",
                symbol = "SYN",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("99.20"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        paper.submit(
            OrderRequest.StopLimit(
                id = "stop-limit",
                symbol = "SYN",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("99.70"),
                limitPrice = Money.of("98.00"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        paper.onTick(tick("99.00", 2L))

        assertThat(fills).containsExactly("stop-limit", "touched")
    }
}
