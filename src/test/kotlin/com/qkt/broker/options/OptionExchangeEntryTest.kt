package com.qkt.broker.options

import com.qkt.broker.liquidation.liquidationReason
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionExchangeEntryTest {
    private fun fixture(dir: Path) =
        OptionExchangeFixture(dir).also {
            it.chain.store(
                Triple("2026-10-01T01:00:00Z", "100", 0L),
                Triple("2026-10-01T04:00:00Z", "100", 0L),
            )
        }

    @Test
    fun `the venue supports its catalogued options and nothing else`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)

        assertThat(f.exchange.supports(f.symbol)).isTrue()
        assertThat(f.exchange.supports("DERIBIT:BTC_USDC_PERPETUAL")).isFalse()
    }

    @Test
    fun `a stop order is refused because the venue takes only market and limit orders`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        val stop =
            OrderRequest.Stop("st", f.symbol, Side.BUY, BigDecimal("0.1"), BigDecimal("100"), TimeInForce.GTC, f.clock.time, "s1")

        val ack = f.exchange.submit(stop)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isEqualTo("the option venue takes market and limit orders, not Stop")
        assertThat(f.last<BrokerEvent.OrderRejected>().clientOrderId).isEqualTo("st")
    }

    @Test
    fun `a buy limit that snaps to zero on the price grid is refused`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)

        val ack = f.exchange.submit(f.limit(Side.BUY, "0.00000001"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).startsWith("limit ").endsWith(" on ${f.symbol} snaps to no price above zero")
    }

    @Test
    fun `a day limit lapses at the end of its session`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T00:30:00Z", onSymbol = "OTHER")
        f.exchange.submit(f.limit(Side.BUY, "50", TimeInForce.DAY))

        f.tick("2026-10-01T04:00:00Z")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderCancelled>()).isEmpty()
        f.tick("2026-10-02T00:00:01Z", onSymbol = "OTHER")

        assertThat(f.last<BrokerEvent.OrderCancelled>().reason).startsWith("expired at 2026-10-0")
    }

    @Test
    fun `liquidating a contract cancels its working orders with the liquidation reason`(
        @TempDir dir: Path,
    ) {
        val f = fixture(dir)
        f.tick("2026-10-01T00:30:00Z", onSymbol = "OTHER")
        val resting = f.limit(Side.BUY, "50")
        f.exchange.submit(resting)

        f.exchange.liquidate(f.symbol)

        val cancelled = f.last<BrokerEvent.OrderCancelled>()
        assertThat(cancelled.clientOrderId).isEqualTo(resting.id)
        assertThat(cancelled.reason).isEqualTo(liquidationReason(f.symbol))
    }
}
