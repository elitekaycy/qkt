package com.qkt.broker.options

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The 92000 call expires 2026-10-02T08:00Z; it is bought at 01:00's quote (ask 105) for 0.1 contracts. */
class OptionExpiryTest {
    private fun bought(
        dir: Path,
        deliveryPrice: String? = "95000",
        right: String = "call",
    ) = OptionExchangeFixture(dir, deliveryPrice, right).also {
        it.chain.store(Triple("2026-10-01T01:00:00Z", "100", 0L))
        it.tick("2026-10-01T00:30:00Z", onSymbol = "OTHER")
        it.exchange.submit(it.market(Side.BUY))
        it.tick("2026-10-01T01:00:00Z")
    }

    @Test
    fun `a held call settles at its intrinsic value on the first tick of any symbol at expiry`(
        @TempDir dir: Path,
    ) {
        val f = bought(dir)
        f.tick("2026-10-02T07:59:59Z", onSymbol = "OTHER")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)

        f.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER")

        val settled = f.last<BrokerEvent.OrderFilled>()
        assertThat(settled.exitReason).isEqualTo(ExitReason.EXPIRY)
        assertThat(settled.side).isEqualTo(Side.SELL)
        assertThat(settled.quantity).isEqualByComparingTo("0.1")
        assertThat(settled.price).isEqualByComparingTo("3000")
        assertThat(settled.updatesOrderExecution).isFalse()
        // min(0.015% of 95000 = 14.25, 12.5% of 3000) x 0.1 contracts.
        assertThat(
            settled.typedVenueCosts
                .single()
                .amount.amount,
        ).isEqualByComparingTo("1.425")
        assertThat(
            f.settlements.entries
                .single()
                .price,
        ).isEqualByComparingTo("3000")
        f.tick("2026-10-02T09:00:00Z", onSymbol = "OTHER")
        assertThat(f.events.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(2)
    }

    @Test
    fun `an out-of-the-money expiry settles at zero with no delivery fee`(
        @TempDir dir: Path,
    ) {
        val f = bought(dir, deliveryPrice = "90000")

        f.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER")

        val settled = f.last<BrokerEvent.OrderFilled>()
        assertThat(settled.price).isEqualByComparingTo("0")
        assertThat(settled.typedVenueCosts).isEmpty()
    }

    @Test
    fun `working orders lapse at expiry and a missing delivery price fails naming the catalog refresh`(
        @TempDir dir: Path,
    ) {
        val f = bought(dir, deliveryPrice = null)
        f.exchange.submit(f.limit(Side.SELL, "500"))

        assertThatThrownBy { f.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER") }
            .hasMessageContaining("qkt fetch DERIBIT:BTC_USDC --catalog")
        assertThat(f.last<BrokerEvent.OrderCancelled>().reason).contains("expired")
    }

    @Test
    fun `a put settles at the strike less the delivery price, and at zero above the strike`(
        @TempDir dir: Path,
    ) {
        val itm = bought(dir.resolve("itm"), deliveryPrice = "90000", right = "put")
        itm.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER")
        assertThat(itm.last<BrokerEvent.OrderFilled>().price).isEqualByComparingTo("2000")

        val otm = bought(dir.resolve("otm"), deliveryPrice = "95000", right = "put")
        otm.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER")
        assertThat(otm.last<BrokerEvent.OrderFilled>().price).isEqualByComparingTo("0")
    }
}
