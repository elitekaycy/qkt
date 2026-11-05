package com.qkt.app

import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import com.qkt.common.Side
import com.qkt.connector.gateway.WireInstrument
import com.qkt.connector.gateway.WireSettlement
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * VGP v1 carries no leg, position or realized P&L on a deal, and no fill at all for a settlement: only what
 * qkt booked states them. A real live session on a [com.qkt.connector.gateway.FakeGateway] books each, stops,
 * and a second session on the same state reads the account's deals past the 60-second grace (#1570).
 */
class LiveGatewayDealsRestartTest {
    private val expiry = System.currentTimeMillis() + 30 * 86_400_000L
    private val option =
        WireInstrument(
            "BTC_USDC-25DEC26-92000-C",
            "option",
            "USDC",
            "1",
            "0.5",
            "0.001",
            "0.001",
            expiry = expiry,
            strike = "92000",
            right = "call",
            underlying = "BTC_USDC",
        )
    private val dated =
        WireInstrument("BTC_USDC-25DEC26", "future", "USDC", "1", "0.5", "0.001", "0.001", expiry = expiry)

    private fun market(
        instrument: WireInstrument? = null,
        quantity: String = "0.5",
    ): LiveGatewayRestartFixture {
        val entry = { symbol: String ->
            OrderRequest.Market("e1", symbol, Side.BUY, BigDecimal(quantity), TimeInForce.GTC, 5L, "guard")
        }
        return instrument?.let { LiveGatewayRestartFixture(it, entry) } ?: LiveGatewayRestartFixture(entry = entry)
    }

    private fun LiveGatewayRestartFixture.filled(
        running: LiveGatewayRestartFixture.Running,
        quantity: String = "0.5",
    ) {
        await { fake.submits.isNotEmpty() }
        fake.act { fill(sent("e1"), "f1", quantity, "70000", agoMs(180_000)) }
        await { running.held().compareTo(BigDecimal(quantity)) == 0 }
    }

    private fun assertEntry(deals: List<BrokerDeal>) {
        val deal = deals.single { it.dealTicket == "f1" }
        assertThat(deal.entry).isEqualTo("IN")
        assertThat(deal.qty).isEqualByComparingTo("0.5")
        assertThat(deal.strategyId).isEqualTo("guard")
        assertThat(deal.realizedSource).isEqualTo("engine")
        assertThat(deal.positionTicket).isNotNull()
    }

    private fun assertClose(
        deals: List<BrokerDeal>,
        f: LiveGatewayRestartFixture,
    ) {
        val close = deals.single { it.closeReason == CloseReason.EXPIRY }
        assertThat(close.dealTicket).isEqualTo("settle:${f.code}:guard")
        assertThat(close.entry).isEqualTo("OUT")
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.qty).isEqualByComparingTo("0.5")
        assertThat(close.price).isEqualByComparingTo("70500")
        assertThat(close.profit).isEqualByComparingTo("250")
        assertThat(close.strategyId).isEqualTo("guard")
    }

    /** The contract settles and qkt books it; [restart] reads the deals in the next process, else in this one. */
    private fun holdsThenSettles(
        instrument: WireInstrument,
        state: Path,
        restart: Boolean,
    ) = market(instrument).use { f ->
        val before = f.start(state)
        var after: LiveGatewayRestartFixture.Running? = null
        try {
            f.filled(before)
            f.fake.act { settle(WireSettlement(instrument.code, "70500", agoMs(120_000))) }
            f.await { before.held().signum() == 0 }
            if (restart) {
                before.stop()
                after = f.start(state)
                f.await { f.fake.quotes.open > 0 }
            }
            val deals = (after ?: before).deals()
            assertClose(deals, f)
            assertEntry(deals)
        } finally {
            (after ?: before).stop()
        }
    }

    @Test
    fun `an option's settlement is a deal before any restart`(
        @TempDir state: Path,
    ) = holdsThenSettles(option, state, restart = false)

    @Test
    fun `an option's settlement is still a deal after a restart past the grace`(
        @TempDir state: Path,
    ) = holdsThenSettles(option, state, restart = true)

    @Test
    fun `a dated future's settlement is a deal before any restart`(
        @TempDir state: Path,
    ) = holdsThenSettles(dated, state, restart = false)

    @Test
    fun `a directly held dated future's settlement is still a deal after a restart past the grace`(
        @TempDir state: Path,
    ) = holdsThenSettles(dated, state, restart = true)

    @Test
    fun `an ordinary fill is a deal after a restart past the grace, over further restarts and repeated reads`(
        @TempDir state: Path,
    ) = market().use { f ->
        val before = f.start(state)
        f.filled(before)
        before.stop()

        val second = f.start(state)
        val third =
            try {
                assertEntry(second.deals())
                assertEntry(second.deals())
                second.stop()
                f.start(state)
            } catch (e: Throwable) {
                second.stop()
                throw e
            }
        try {
            assertEntry(third.deals())
        } finally {
            third.stop()
        }
    }

    @Test
    fun `an order filled across a restart keeps each fill's own facts`(
        @TempDir state: Path,
    ) {
        market().use { f ->
            val before = f.start(state)
            f.filled(before, "0.3")
            before.stop()

            val after = f.start(state)
            try {
                f.await { f.fake.quotes.open > 0 && after.held().compareTo(BigDecimal("0.3")) == 0 }
                f.fake.act { fill(f.sent("e1"), "f2", "0.2", "71000", agoMs(100_000)) }
                f.await { after.held().compareTo(BigDecimal("0.5")) == 0 }

                val deals = after.deals().associateBy { it.dealTicket }
                assertThat(deals.keys).containsExactlyInAnyOrder("f1", "f2")
                assertThat(deals.getValue("f1").qty).isEqualByComparingTo("0.3")
                assertThat(deals.getValue("f1").entry).isEqualTo("IN")
                assertThat(deals.getValue("f2").qty).isEqualByComparingTo("0.2")
                assertThat(deals.getValue("f2").positionTicket).isEqualTo(deals.getValue("f1").positionTicket)
            } finally {
                after.stop()
            }
        }
    }

    @Test
    fun `a contract still held when it settled while qkt was down is booked and reported by the next session`(
        @TempDir state: Path,
    ) = market(option).use { f ->
        val before = f.start(state)
        f.filled(before)
        before.stop()
        f.fake.act { settle(WireSettlement(option.code, "70500", agoMs(120_000))) }

        val after = f.start(state)
        try {
            f.await { after.held().signum() == 0 }
            assertClose(after.deals(), f)
        } finally {
            after.stop()
        }
    }

    private fun agoMs(ms: Long) = System.currentTimeMillis() - ms
}
