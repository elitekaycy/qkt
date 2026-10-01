package com.qkt.broker.options

import com.qkt.accounting.CostKind
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.TimeInForce
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Stored marks: 100 at 01:00 (sides 95/105), 120 at 02:00 (110/130), 100 aged 2h at 03:00 (no sides),
 * 100 at 04:00 (95/105).
 */
class OptionExchangeTest {
    private fun fixture(dir: Path) =
        OptionExchangeFixture(dir).also {
            it.chain.store(
                Triple("2026-10-01T01:00:00Z", "100", 0L),
                Triple("2026-10-01T02:00:00Z", "120", 0L),
                Triple("2026-10-01T03:00:00Z", "100", 7_200_000L),
                Triple("2026-10-01T04:00:00Z", "100", 0L),
            )
        }

    @Test
    fun `a market buy decided on a snapshot fills at the next snapshot's ask with its fee`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T01:00:00Z")
        f.exchange.submit(f.market(Side.BUY))
        f.tick("2026-10-01T01:00:00Z")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()

        f.tick("2026-10-01T02:00:00Z")

        val fill = f.last<BrokerEvent.OrderFilled>()
        assertThat(fill.price).isEqualByComparingTo("130")
        assertThat(fill.quantity).isEqualByComparingTo("0.1")
        assertThat(fill.timestamp).isEqualTo(f.clock.time)
        // min(0.03% of 83000 = 24.9, 12.5% of 130 = 16.25) x 0.1 contracts.
        val fee = fill.typedVenueCosts.single()
        assertThat(fee.kind).isEqualTo(CostKind.EXCHANGE_FEE)
        assertThat(fee.amount.amount).isEqualByComparingTo("1.625")
        assertThat(fee.amount.currency).isEqualTo("USDC")
    }

    @Test
    fun `a market order meeting a quote without its side is cancelled, never filled at the mark`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T02:30:00Z", onSymbol = "OTHER")
        f.exchange.submit(f.market(Side.BUY))

        f.tick("2026-10-01T03:00:00Z")

        assertThat(f.last<BrokerEvent.OrderCancelled>().reason).contains("no ask")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
    }

    @Test
    fun `a market order with no quote of its contract within the quote age is cancelled`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T04:30:00Z", onSymbol = "OTHER")
        f.exchange.submit(f.market(Side.BUY))

        f.tick("2026-10-01T05:29:59Z", onSymbol = "OTHER")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderCancelled>()).isEmpty()
        f.tick("2026-10-01T05:30:00Z", onSymbol = "OTHER")

        assertThat(f.last<BrokerEvent.OrderCancelled>().reason).contains("no quote")
    }

    @Test
    fun `a buy limit is snapped down and fills at its limit once the ask reaches it`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T01:30:00Z", onSymbol = "OTHER")
        f.exchange.submit(f.limit(Side.BUY, "112"))

        f.tick("2026-10-01T02:00:00Z")
        f.tick("2026-10-01T03:00:00Z")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
        f.tick("2026-10-01T04:00:00Z")

        assertThat(f.last<BrokerEvent.OrderFilled>().price).isEqualByComparingTo("110")
    }

    @Test
    fun `an immediate-or-cancel limit gets one look at the next quote`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T01:30:00Z", onSymbol = "OTHER")
        f.exchange.submit(f.limit(Side.BUY, "110", TimeInForce.IOC))

        f.tick("2026-10-01T02:00:00Z")

        assertThat(f.last<BrokerEvent.OrderCancelled>().clientOrderId).isEqualTo("o1")
    }

    @Test
    fun `selling more than is held is refused and a held position sells at the bid`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T00:30:00Z", onSymbol = "OTHER")
        assertThat(f.exchange.submit(f.market(Side.SELL)).accepted).isFalse()
        f.exchange.submit(f.market(Side.BUY))
        f.tick("2026-10-01T01:00:00Z")

        assertThat(f.exchange.submit(f.market(Side.SELL, "0.2")).accepted).isFalse()
        assertThat(f.exchange.submit(f.market(Side.SELL)).accepted).isTrue()
        f.tick("2026-10-01T02:00:00Z")

        assertThat(f.last<BrokerEvent.OrderFilled>().price).isEqualByComparingTo("110")
        assertThat(f.last<BrokerEvent.OrderFilled>().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `orders on an expired contract, a non-option, a size below the minimum, and cancels are answered`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T00:30:00Z", onSymbol = "OTHER")
        assertThat(f.exchange.submit(f.market(Side.BUY, "0.001")).rejectReason).contains("volumeMin")
        assertThat(f.exchange.submit(f.market(Side.BUY).copy(symbol = "DERIBIT:BTC_USDC_PERPETUAL")).accepted).isFalse()
        val resting = f.limit(Side.BUY, "50")
        f.exchange.submit(resting)
        f.exchange.cancel(resting.id)
        assertThat(f.last<BrokerEvent.OrderCancelled>().clientOrderId).isEqualTo(resting.id)

        f.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER")
        assertThat(f.exchange.submit(f.market(Side.BUY)).rejectReason).contains("expired")
    }
}
