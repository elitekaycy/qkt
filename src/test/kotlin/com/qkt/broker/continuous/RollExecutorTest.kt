package com.qkt.broker.continuous

import com.qkt.broker.continuous.ContinuousFixture.Companion.ms
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Rolls on the fixture's September roll (08:00 on 2024-09-19, references 63000 -> 63800, so the
 * December contract sits 800 above the series). One tick of slippage on every market leg.
 */
class RollExecutorTest {
    private val f = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", slippageTicks = 1)
    private val roll = ms("2024-09-19T08:00:00Z")
    private val sep = "BINANCE_UM:BTCUSDT_240927"
    private val dec = "BINANCE_UM:BTCUSDT_241227"

    @Test
    fun `a long is carried to the next contract at the reference prices and its cost is booked`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))

        f.tick("63005.0", atMs = roll)

        val entry = f.ledger.entries.single()
        assertThat(entry.from).isEqualTo(sep)
        assertThat(entry.to).isEqualTo(dec)
        assertThat(entry.atMs).isEqualTo(roll)
        assertThat(entry.quantity).isEqualByComparingTo("0.01")
        assertThat(entry.fromFill).isEqualByComparingTo("62999.9")
        assertThat(entry.toFill).isEqualByComparingTo("63800.1")
        assertThat(entry.cost).isEqualByComparingTo("0.002")
        val cost = f.costs.single()
        assertThat(cost.strategyId).isEqualTo("s")
        assertThat(cost.symbol).isEqualTo(f.front)
        assertThat(cost.amount).isEqualByComparingTo("0.002")
        assertThat(cost.reason).isEqualTo("roll $sep->$dec")
    }

    @Test
    fun `the engine sees none of the roll's orders`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))

        f.tick("63005.0", atMs = roll)

        assertThat(f.events.map { it::class.simpleName to (it as BrokerEvent.OrderEvent).clientOrderId })
            .containsExactly("OrderAccepted" to "entry", "OrderFilled" to "entry")
    }

    @Test
    fun `after the roll an exit trades the new contract and is priced in the series`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        f.tick("63005.0", atMs = roll)

        f.tick("63020.0", atMs = roll + 900_000L)
        f.broker.submit(f.market("exit", Side.SELL))

        // December trades 63820.0; the sell slips to 63819.9, which is 63019.9 in the series.
        assertThat(f.only<BrokerEvent.OrderFilled>().last().price).isEqualByComparingTo("63019.9")
    }

    @Test
    fun `a short is bought back on the old contract and sold on the new one`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.SELL))

        f.tick("63005.0", atMs = roll)

        val entry = f.ledger.entries.single()
        assertThat(entry.quantity).isEqualByComparingTo("-0.01")
        assertThat(entry.fromFill).isEqualByComparingTo("63000.1")
        assertThat(entry.toFill).isEqualByComparingTo("63799.9")
        assertThat(entry.cost).isEqualByComparingTo("0.002")
    }

    @Test
    fun `a resting stop is re-placed on the new contract at the same series level`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        f.broker.submit(f.stop("protect", Side.SELL, "62900.05"))
        f.tick("63005.0", atMs = roll)

        f.tick("62900.0", atMs = roll + 900_000L)

        // Re-placed at 63700.05 -> snapped down to 63700.0; it slips to 63699.9, which is 62899.9 in the series.
        val fill = f.only<BrokerEvent.OrderFilled>().last()
        assertThat(fill.clientOrderId).isEqualTo("protect")
        assertThat(fill.price).isEqualByComparingTo("62899.9")
        assertThat(f.only<BrokerEvent.OrderAccepted>().map { it.clientOrderId }).containsExactly("entry", "protect")
        assertThat(f.only<BrokerEvent.OrderCancelled>()).isEmpty()
    }

    @Test
    fun `an engine cancel after the roll cancels the re-placed order`() {
        f.tick("63010.0")
        f.broker.submit(f.stop("protect", Side.SELL, "62900.0"))
        f.tick("63005.0", atMs = roll)

        f.broker.cancel("protect")
        f.tick("62000.0", atMs = roll + 900_000L)

        assertThat(f.only<BrokerEvent.OrderCancelled>().single().clientOrderId).isEqualTo("protect")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
    }

    @Test
    fun `roll fees reported by the venue are part of the roll cost`() {
        val taker = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", slippageTicks = 1, takerFeeRate = "0.0005")
        taker.tick("63010.0")
        taker.broker.submit(taker.market("entry", Side.BUY))

        taker.tick("63005.0", atMs = roll)

        // 0.01 x 62999.9 x 0.0005 + 0.01 x 63800.1 x 0.0005 = 0.634, plus 0.002 of slippage.
        assertThat(
            taker.ledger.entries
                .single()
                .fees,
        ).isEqualByComparingTo("0.634")
        assertThat(taker.costs.single().amount).isEqualByComparingTo("0.636")
    }

    @Test
    fun `a flat stream rolls without trading`() {
        f.tick("63010.0")

        f.tick("63005.0", atMs = roll)

        assertThat(f.ledger.entries).isEmpty()
        assertThat(f.costs).isEmpty()
    }

    @Test
    fun `an order sent at the roll before the stream's own tick rolls first`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))

        f.clock.time = roll
        f.broker.submit(f.market("exit", Side.SELL))

        // Carried to December at the references, then sold there at 63800.0 less a tick: 62999.9 in the series.
        assertThat(
            f.ledger.entries
                .single()
                .to,
        ).isEqualTo(dec)
        assertThat(f.only<BrokerEvent.OrderFilled>().last().price).isEqualByComparingTo("62999.9")
    }
}
