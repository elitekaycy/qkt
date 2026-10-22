package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.CloseReason
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.broker.VenueAccountId
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.broker.VenueInstrument
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every session's poller reports the whole venue account, so what qkt resolved about a position in the
 * session that traded it (its owner, its initial stop and risk, why it closed) is stated by every other
 * session's poller too. Live, 2026-10-21: the BTC session's poller sent the ETH bracket position with no
 * stop or risk, and the timed exit's deal as SIGNAL while its fill said TIMED. The stop is the one the
 * venue holds: `STOP_LOSS BY 3` re-anchored on the fill (2516.69 - 3), not the level sent before it.
 */
class BrokerStatePollerSharedFactsTest : BrokerStatePollerFixture() {
    private val symbol = "FAKE:XAUUSD"
    private val now = 1_700_000_000_000L
    private val shared = PositionFacts()
    private val venues =
        InsightsVenues({ listOf(Described) }).also { it.accountCurrency("FAKE", "USD") }

    private object Described : Broker, VenueDescribed {
        override val name = "FAKE"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, null, accepted = true)

        override fun cancel(orderId: String) {}

        override fun venueIdentity() = VenueIdentity("mt5", "fake", "FAKE", VenueAccountId.mt5("1", "Fake"))

        override fun fillPositionId(brokerOrderId: String?) = brokerOrderId

        override fun instrument(qktSymbol: String) = VenueInstrument("XAUUSDm", "cfd", BigDecimal.ONE, null, "USD")
    }

    private fun session() = InsightsFillFacts(venues, null, { PositionAccountingMode.HEDGING }, shared = shared)

    @Test
    fun `a session that never traded the position states its owner, venue stop, risk and close reason`() {
        val traded = session()
        traded.submitted(bracket())
        traded.seen("e-1", "T1", symbol, Side.BUY, BigDecimal("0.1"), now - 9_000, null)
        val entry = traded.fillFields("e-1", "bracket", symbol, BigDecimal("2516.69"), BigDecimal("0.1"), now - 9_000)
        traded.seen("dsl-bracket--1-exit-close", "T1", symbol, Side.SELL, BigDecimal("0.1"), now - 600, null)
        traded.fillFields(
            "dsl-bracket--1-exit-close",
            "bracket",
            symbol,
            BigDecimal("2515"),
            BigDecimal("0.1"),
            now - 600,
        )

        assertThat(entry["initialStop"] as BigDecimal).isEqualByComparingTo("2513.69")
        assertThat(entry["initialTarget"] as BigDecimal).isEqualByComparingTo("2519.69")
        assertThat(entry["riskAmount"] as BigDecimal).isEqualByComparingTo("0.3")

        val broker = FakeBroker()
        broker.tickets = listOf(ticket("T1"))
        broker.allDeals =
            listOf(
                deal(
                    "d-out",
                    ts = now - 500,
                    positionTicket = "T1",
                    entry = "OUT",
                ).copy(closeReason = CloseReason.SIGNAL),
            )
        BrokerStatePoller(
            brokers = listOf(broker),
            sink = sink,
            attribution = TicketAttribution(),
            deployedIds = { listOf("bracket", "other") },
            backfillDays = 1L,
            clock = { now },
            venues = venues,
            fills = session(),
        ).pollOnce()

        val bodies = collectBodies("deal-FAKE-d-out", "initialStop")
        val position = bodies.substringAfter("\"positions\"").substringBefore("deal-FAKE-d-out")
        assertThat(position).contains("\"strategyId\":\"bracket\"").contains("2513.69").contains("\"riskAmount\":0.3")
        assertThat(bodies.substringAfter("deal-FAKE-d-out")).contains("TIMED").contains("\"strategyId\":\"bracket\"")
    }

    @Test
    fun `a close the venue decided keeps the venue's reason`() {
        val traded = session()
        traded.seen("e-1", "T1", symbol, Side.BUY, BigDecimal("0.1"), now - 9_000, null)
        traded.fillFields("e-1", "bracket", symbol, BigDecimal("2516.69"), BigDecimal("0.1"), now - 9_000)
        traded.seen("dsl-bracket--1-exit-close", "T1", symbol, Side.SELL, BigDecimal("0.1"), now - 600, null)
        traded.fillFields(
            "dsl-bracket--1-exit-close",
            "bracket",
            symbol,
            BigDecimal("2515"),
            BigDecimal("0.1"),
            now - 600,
        )

        assertThat(shared.closeReason(symbol, "T1", now - 500)).isEqualTo(CloseReason.TIMED)
        assertThat(shared.ownerOf(symbol, "T1")).isEqualTo("bracket")
        assertThat(session().closeReason(symbol, "T2", now)).isNull()
    }

    private fun bracket() =
        OrderRequest.Bracket(
            id = "b-1",
            symbol = symbol,
            side = Side.BUY,
            quantity = BigDecimal("0.1"),
            entry = OrderRequest.Market("e-1", symbol, Side.BUY, BigDecimal("0.1"), TimeInForce.GTC, 1),
            takeProfit = BigDecimal("2518.86"),
            stopLoss = StopLossSpec.Fixed(BigDecimal("2512.86")),
            timeInForce = TimeInForce.GTC,
            timestamp = 1,
            takeProfitAst = ChildBy(NumLit(BigDecimal("3"))),
            stopLossAst = ChildBy(NumLit(BigDecimal("3"))),
        )
}
