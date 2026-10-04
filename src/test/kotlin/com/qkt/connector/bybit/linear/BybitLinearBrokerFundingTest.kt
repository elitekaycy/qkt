package com.qkt.connector.bybit.linear

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.connector.bybit.linear.BybitLinearFundingTest.Companion.BYBIT_DOC_FUNDING
import com.qkt.events.BrokerEvent
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FundingCharged
import com.qkt.positions.StrategyPositionTracker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The Bybit linear broker books its perpetuals' funding and never mistakes it for a fill. */
class BybitLinearBrokerFundingTest {
    private val clock = FixedClock(1_682_560_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val client = FakeBybitClient()
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val charged = mutableListOf<FundingCharged>()
    private val empty = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
    private val listed = """{"retCode":0,"retMsg":"OK","result":{"list":[$BYBIT_DOC_FUNDING],"nextPageCursor":""}}"""

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
        bus.subscribe<FundingCharged> { charged += it }
        listOf("/v5/order/realtime", "/v5/execution/list", "/v5/account/wallet-balance", "/v5/position/list")
            .forEach { client.responses[it] = empty }
    }

    private fun broker() = BybitLinearBroker(client, bus, clock, StrategyPositionTracker().account)

    @Test
    fun `startup reads the last seven days of funding and publishes each record as funding charged`() {
        client.responsesByPredicate +=
            { path: String, q: String -> path == "/v5/execution/list" && "execType=Funding" in q } to listed

        broker().shutdown()

        assertThat(client.posts.map { it.body })
            .anyMatch { "execType=Funding" in it && "startTime=${clock.now() - FUNDING_REPLAY_MS}" in it }
        assertThat(charged.map { it.fundingId to it.symbol })
            .containsExactly("11f1c4ed-ff20-4d73-acb7-96e43a917f25" to "BYBIT_LINEAR:BTCUSDT")
        assertThat(fills).isEmpty()
    }

    @Test
    fun `a funding execution in the execution replay is funding, not a fill, and is published once`() {
        client.responses["/v5/execution/list"] = listed

        broker().shutdown()

        assertThat(charged).hasSize(1)
        assertThat(fills).isEmpty()
    }

    @Test
    fun `a funding execution on the private stream is published as funding charged, not a fill`() {
        val broker = broker()
        val frame = """{"topic":"execution","data":[${BYBIT_DOC_FUNDING.replace(
            "{\"symbol\"",
            "{\"category\":\"linear\",\"symbol\"",
        )}]}"""

        client.emitWsFrame("execution", Json.parseToJsonElement(frame).jsonObject)
        broker.shutdown()

        assertThat(charged.map { it.amount.toPlainString() to it.basis.toPlainString() }).containsExactly(
            "0.6364003" to "-0.011",
        )
        assertThat(fills).isEmpty()
    }

    @Test
    fun `a liquidation in the execution replay is left to the position reconcile, never a fill`() {
        client.responses["/v5/execution/list"] =
            listed.replace("\"execType\":\"Funding\"", "\"execType\":\"BustTrade\"")

        broker().shutdown()

        assertThat(fills).isEmpty()
        assertThat(charged).isEmpty()
    }
}
