package com.qkt.observe.insights

import com.qkt.backtest.FillState
import com.qkt.broker.Broker
import com.qkt.broker.CloseReason
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.broker.VenueInstrument
import com.qkt.common.Side
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What an `order.filled` and a `trade.closed` state beyond the connector's own fields. */
class InsightsFillFactsTest {
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private var held: Position? = null
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = held

            override fun allPositions() = listOfNotNull(held).associateBy { it.symbol }
        }

    private class Venue(
        private val namesTickets: Boolean,
    ) : Broker,
        VenueDescribed {
        override val name = "DERIBIT"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, null, accepted = true)

        override fun cancel(orderId: String) {}

        override fun venueIdentity() = VenueIdentity("vgp", "deribit", "DERIBIT", "vgp:deribit:x")

        override fun fillPositionId(brokerOrderId: String?) = brokerOrderId.takeIf { namesTickets }

        override fun instrument(qktSymbol: String) =
            VenueInstrument("BTC_USDC-PERPETUAL", "perpetual", BigDecimal.ONE, BigDecimal("0.1"), "USDC")
    }

    private fun facts(
        namesTickets: Boolean = false,
        mode: PositionAccountingMode = PositionAccountingMode.NETTING,
    ): InsightsFillFacts {
        val venues = InsightsVenues({ listOf(Venue(namesTickets)) })
        venues.accountCurrency("DERIBIT", "USDC")
        return InsightsFillFacts(venues, positions, { mode })
    }

    @Test
    fun `an entry fill states its leg, new position, strategy, bracket and risk`() {
        val f = facts()
        f.submitted(bracket("e-1", stop = "95000", target = "110000"))
        f.seen("e-1", "v-1", symbol, Side.BUY, BigDecimal("0.5"), 1_000, null)

        val fields = f.fillFields("e-1", "a", symbol, BigDecimal("100000"), BigDecimal("0.5"), 1_000)

        assertThat(fields["legEntry"]).isEqualTo("IN")
        assertThat(fields["positionId"]).isEqualTo("$symbol#1000")
        assertThat(fields["strategyId"]).isEqualTo("a")
        assertThat(fields["attribution"]).isEqualTo("order")
        assertThat(fields["closeReason"]).isNull()
        assertThat(fields["initialStop"] as BigDecimal).isEqualByComparingTo("95000")
        assertThat(fields["initialTarget"] as BigDecimal).isEqualByComparingTo("110000")
        assertThat(fields["riskAmount"] as BigDecimal).isEqualByComparingTo("2500")
        assertThat((fields["instrument"] as Map<*, *>)["venueCode"]).isEqualTo("BTC_USDC-PERPETUAL")
        assertThat(f.entryFields("$symbol#1000")["riskAmount"] as BigDecimal).isEqualByComparingTo("2500")
    }

    @Test
    fun `the protective stop qkt placed replaces the bracket's requested one`() {
        val f = facts()
        f.submitted(bracket("e-1", stop = "95000", target = "110000"))
        f.submitted(
            OrderRequest.Stop(
                "b-e-1-sl",
                symbol,
                Side.SELL,
                BigDecimal("0.5"),
                BigDecimal("96000"),
                TimeInForce.GTC,
                1,
            ),
        )
        f.seen("e-1", null, symbol, Side.BUY, BigDecimal("0.5"), 1_000, null)

        val fields = f.fillFields("e-1", "a", symbol, BigDecimal("100000"), BigDecimal("0.5"), 1_000)

        assertThat(fields["initialStop"] as BigDecimal).isEqualByComparingTo("96000")
    }

    @Test
    fun `a closing fill states the position it closed and why, the same on order filled and trade closed`() {
        val f = facts()
        held = Position(symbol, BigDecimal("0.5"), BigDecimal("100000"), 1_000)
        f.seen("b-e-1-sl", null, symbol, Side.SELL, BigDecimal("0.5"), 2_000, null)

        val filled = f.fillFields("b-e-1-sl", "a", symbol, BigDecimal("96000"), BigDecimal("0.5"), 2_000)
        val closed =
            f.closedFields(
                Trade("b-e-1-sl", symbol, BigDecimal("96000"), BigDecimal("0.5"), Side.SELL, 2_000),
                "a",
                FillState(held, null, held, null),
            )

        assertThat(filled["legEntry"]).isEqualTo("OUT")
        assertThat(filled["positionId"]).isEqualTo("$symbol#1000")
        assertThat(filled["closeReason"]).isEqualTo(CloseReason.STOP)
        assertThat(closed.filterKeys { it in LEG }).isEqualTo(filled.filterKeys { it in LEG })
    }

    @Test
    fun `a venue that names its positions keeps its ticket, and a hedging leg follows the order's intent`() {
        val f = facts(namesTickets = true, mode = PositionAccountingMode.HEDGING)
        held = Position(symbol, BigDecimal("1"), BigDecimal("100000"), 1_000)
        f.seen("e-2", "777", symbol, Side.SELL, BigDecimal("1"), 2_000, null)
        f.seen("e-1", "555", symbol, Side.SELL, BigDecimal("1"), 3_000, ExitReason.TAKE_PROFIT)

        val opened = f.fillFields("e-2", "a", symbol, BigDecimal("1"), BigDecimal("1"), 2_000)
        val closed = f.fillFields("e-1", "a", symbol, BigDecimal("1"), BigDecimal("1"), 3_000)

        assertThat(opened["legEntry"]).isEqualTo("IN")
        assertThat(opened["positionId"]).isEqualTo("777")
        assertThat(closed["legEntry"]).isEqualTo("OUT")
        assertThat(closed["positionId"]).isEqualTo("555")
        assertThat(closed["closeReason"]).isEqualTo(CloseReason.TAKE_PROFIT)
    }

    private fun bracket(
        entryId: String,
        stop: String,
        target: String,
    ) = OrderRequest.Bracket(
        id = "b-$entryId",
        symbol = symbol,
        side = Side.BUY,
        quantity = BigDecimal("0.5"),
        entry = OrderRequest.Market(entryId, symbol, Side.BUY, BigDecimal("0.5"), TimeInForce.GTC, 1),
        takeProfit = BigDecimal(target),
        stopLoss = StopLossSpec.Fixed(BigDecimal(stop)),
        timeInForce = TimeInForce.GTC,
        timestamp = 1,
    )

    private companion object {
        val LEG = setOf("positionId", "legEntry", "closeReason", "strategyId", "attribution")
    }
}
