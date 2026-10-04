package com.qkt.broker.options

import com.qkt.common.Side
import com.qkt.derivatives.options.chain.OptionChainFixture.Companion.ms
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The fixture's 92000 call, bought at 01:00's quote (ask 105) for 0.1 contracts; 02:00 quotes it at mark 60. */
class OptionLiquidationTest {
    private fun bought(dir: Path) =
        OptionExchangeFixture(dir).also {
            it.chain.store(Triple("2026-10-01T01:00:00Z", "100", 0L), Triple("2026-10-01T02:00:00Z", "60", 0L))
            it.tick("2026-10-01T00:30:00Z", onSymbol = "OTHER")
            it.exchange.submit(it.market(Side.BUY))
            it.tick("2026-10-01T01:00:00Z")
        }

    private fun OptionExchangeFixture.fills() = events.filterIsInstance<BrokerEvent.OrderFilled>()

    @Test
    fun `a held option is sold at the bid of the moment's quote as a liquidation`(
        @TempDir dir: Path,
    ) {
        val f = bought(dir)
        f.clock.time = ms("2026-10-01T02:00:00Z")

        f.exchange.liquidate(f.symbol)

        val close = f.fills().last()
        assertThat(close.exitReason).isEqualTo(ExitReason.LIQUIDATION)
        assertThat(close.updatesOrderExecution).isFalse()
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("0.1")
        assertThat(close.price).isLessThan(BigDecimal("60"))
        assertThat(close.typedVenueCosts).hasSize(1)
    }

    @Test
    fun `a liquidated option is no longer held, so expiry settles nothing`(
        @TempDir dir: Path,
    ) {
        val f = bought(dir)
        f.clock.time = ms("2026-10-01T02:00:00Z")
        f.exchange.liquidate(f.symbol)

        f.tick("2026-10-02T08:00:00Z", onSymbol = "OTHER")

        assertThat(f.fills().map { it.exitReason }).containsExactly(null, ExitReason.LIQUIDATION)
        assertThat(f.settlements.entries).isEmpty()
    }

    @Test
    fun `without a quote at that moment the option stays held but its working orders are cancelled`(
        @TempDir dir: Path,
    ) {
        val f = bought(dir)
        f.exchange.submit(f.limit(Side.SELL, "500"))
        f.clock.time = ms("2026-10-01T01:30:00Z")

        f.exchange.liquidate(f.symbol)

        assertThat(f.fills()).hasSize(1)
        assertThat(f.last<BrokerEvent.OrderCancelled>().reason).contains("liquidated")
    }
}
