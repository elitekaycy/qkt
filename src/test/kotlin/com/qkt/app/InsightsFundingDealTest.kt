package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.FundingCharged
import com.qkt.execution.LegIntent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A gateway perpetual's funding is venue money: each strategy's share reaches Insights as an `ADJUSTMENT` deal with a
 * `FUNDING` cost, the shape qkt-insights #161 counts in the strategy's net and the account's balance check.
 */
class InsightsFundingDealTest {
    private val perp = "PAPER_DERIBIT:BTC_USDC_PERPETUAL"
    private val server = MockWebServer().apply { start() }
    private val clock = FixedClock(1_000L)
    private val disk =
        object : FundingPersistence {
            val byOwner = HashMap<String, PersistedFunding>()

            override fun saveFunding(
                ownerId: String,
                funding: PersistedFunding,
            ) {
                byOwner[ownerId] = funding
            }

            override fun loadFunding(ownerId: String) = byOwner[ownerId]
        }

    @AfterEach
    fun stop() = server.shutdown()

    /** Two strategies holding 0.2 and 0.1 are funded [amount] on the 0.3 the record names; what the sink sent. */
    private fun funded(
        families: Set<InsightsEventFamily>,
        amount: String = "3",
    ): List<JsonObject> {
        repeat(5) { server.enqueue(MockResponse().setBody("""{"accepted":1}""")) }
        val sink =
            InsightsSink(
                server.url("/ingest").toString(),
                "t",
                "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 100,
            )
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val book = StrategyPositionTracker()
        listOf("s1" to "0.2", "s2" to "0.1").forEach { (id, quantity) ->
            book.applyFill(
                BrokerEvent.OrderFilled(
                    "o-$id",
                    null,
                    perp,
                    Side.BUY,
                    BigDecimal("65000"),
                    BigDecimal(quantity),
                    id,
                    timestamp = 500L,
                ),
                LegIntent.Open("leg-$id", LegRole.PRIMARY),
            )
        }
        FundingBooking(bus, book, disk, MarketPriceTracker(), clock).bind(listOf("s1", "s2"))
        InsightsBusWiring(families).wire(bus, sink, MarketPriceTracker())
        try {
            bus.publish(FundingCharged("tx-1", perp, BigDecimal(amount), "USDC", BigDecimal("0.3"), 2_000L))
            val sent = mutableListOf<JsonObject>()
            val deadline = System.currentTimeMillis() + 1_000L
            while (System.currentTimeMillis() < deadline) {
                val body = server.takeRequest(50, TimeUnit.MILLISECONDS)?.body?.readUtf8() ?: continue
                sent += envelopes(body)
                if (sent.count { it.string("type") == "broker.deal" } >= 2) break
            }
            return sent.filter { it.string("type") == "broker.deal" }
        } finally {
            sink.close()
        }
    }

    private fun envelopes(body: String): List<JsonObject> {
        val root: JsonElement = Json.parseToJsonElement(body)
        return root.jsonObject
            .getValue("events")
            .jsonArray
            .map { it.jsonObject }
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `each strategy's share of a funding record is one deal with its own stable ticket`() {
        val deals = funded(setOf(InsightsEventFamily.DEAL))

        assertThat(deals.map { it.string("id") })
            .containsExactly("deal-PAPER_DERIBIT-funding-tx-1-s1", "deal-PAPER_DERIBIT-funding-tx-1-s2")
        assertThat(deals.map { it.string("strategyId") }).containsExactly("s1", "s2")
        assertThat(deals.map { it.string("ts") }).containsExactly("2000", "2000")
    }

    @Test
    fun `a funding deal says it is funding, moves no quantity and states the cost it paid`() {
        val payload = funded(setOf(InsightsEventFamily.DEAL)).first().getValue("payload").jsonObject

        assertThat(payload.string("dealTicket")).isEqualTo("funding-tx-1-s1")
        assertThat(payload.string("symbol")).isEqualTo(perp)
        assertThat(payload.string("entry")).isEqualTo("ADJUSTMENT")
        assertThat(payload.string("legEntry")).isEqualTo("ADJUSTMENT")
        assertThat(payload.string("adjustment")).isEqualTo("FUNDING")
        assertThat(BigDecimal(payload.string("qty"))).isEqualByComparingTo("0")
        assertThat(BigDecimal(payload.string("profit"))).isEqualByComparingTo("0")
        assertThat(payload.string("strategyId")).isEqualTo("s1")
        assertThat(payload.string("attribution")).isEqualTo("position")
        assertThat(payload.string("positionId")).isEqualTo("$perp#500")
        val cost =
            payload
                .getValue("costs")
                .jsonArray
                .single()
                .jsonObject
        assertThat(cost.string("kind")).isEqualTo("FUNDING")
        assertThat(BigDecimal(cost.string("amount"))).isEqualByComparingTo("2")
        assertThat(cost.string("currency")).isEqualTo("USDC")
    }

    @Test
    fun `funding the venue credits is a negative cost`() {
        val payload = funded(setOf(InsightsEventFamily.DEAL), amount = "-3").last().getValue("payload").jsonObject

        val cost =
            payload
                .getValue("costs")
                .jsonArray
                .single()
                .jsonObject
        assertThat(BigDecimal(cost.string("amount"))).isEqualByComparingTo("-1")
    }

    @Test
    fun `a collector not taking deals gets no funding deal`() {
        assertThat(funded(setOf(InsightsEventFamily.ORDER))).isEmpty()
    }
}
