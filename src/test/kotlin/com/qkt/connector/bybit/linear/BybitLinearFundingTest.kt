package com.qkt.connector.bybit.linear

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.connector.bybit.boundedExecIdSet
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FundingCharged
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A Bybit linear account's `Funding` executions, read as funding charged once each. */
class BybitLinearFundingTest {
    private val clock = FixedClock(1_700_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val client = FakeBybitClient()
    private val charged = mutableListOf<FundingCharged>()
    private val funding = BybitLinearFunding(client, bus, clock, boundedExecIdSet(), pollEveryMs = 300_000L)

    init {
        bus.subscribe<FundingCharged> { charged += it }
    }

    private fun page(vararg records: String) =
        """{"retCode":0,"retMsg":"OK","result":{"list":[${records.joinToString(
            ",",
        )}],"nextPageCursor":"","category":"linear"}}"""

    private fun fundingReads() = client.posts.filter { it.path == "/v5/execution/list" }.map { it.body }

    @Test
    fun `the documented funding execution is a charge of execFee on a short of execQty`() {
        val e = BybitLinearFunding.charged(Json.parseToJsonElement(BYBIT_DOC_FUNDING).jsonObject)

        assertThat(e.fundingId).isEqualTo("11f1c4ed-ff20-4d73-acb7-96e43a917f25")
        assertThat(e.symbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
        assertThat(e.amount).isEqualByComparingTo("0.6364003")
        assertThat(e.basis).isEqualByComparingTo("-0.011")
        assertThat(e.currency).isEqualTo("USDT")
        assertThat(e.fundedAtMs).isEqualTo(1_682_553_600_000L)
    }

    @Test
    fun `funding a long received keeps its negative execFee as a credit on a positive basis`() {
        val received = BYBIT_DOC_FUNDING.replace("\"Sell\"", "\"Buy\"").replace("\"0.6364003\"", "\"-0.6364003\"")

        val e = BybitLinearFunding.charged(Json.parseToJsonElement(received).jsonObject)

        assertThat(e.amount).isEqualByComparingTo("-0.6364003")
        assertThat(e.basis).isEqualByComparingTo("0.011")
    }

    @Test
    fun `the first poll reads seven days of funding executions only and publishes each`() {
        client.responses["/v5/execution/list"] = page(BYBIT_DOC_FUNDING)

        funding.poll()

        val now = clock.now()
        assertThat(fundingReads().single())
            .contains("category=linear", "execType=Funding", "startTime=${now - FUNDING_REPLAY_MS}", "endTime=$now")
        assertThat(charged.map { it.fundingId }).containsExactly("11f1c4ed-ff20-4d73-acb7-96e43a917f25")
    }

    @Test
    fun `a record heard on the stream and read again by a poll is published once`() {
        funding.take(Json.parseToJsonElement(BYBIT_DOC_FUNDING).jsonObject)
        client.responses["/v5/execution/list"] = page(BYBIT_DOC_FUNDING)

        funding.poll()

        assertThat(charged).hasSize(1)
    }

    @Test
    fun `later polls wait out the interval, then read from the last poll with an overlap`() {
        funding.poll()
        val first = clock.now()
        clock.time += 60_000L
        funding.poll()
        clock.time += 300_000L
        funding.poll()

        assertThat(fundingReads()).hasSize(2)
        assertThat(fundingReads().last()).contains("startTime=${first - 600_000L}", "endTime=${clock.now()}")
    }

    @Test
    fun `a failed read is retried from the same point at the next poll`() {
        client.responses["/v5/execution/list"] = """{"retCode":10002,"retMsg":"timestamp expired","result":{}}"""
        funding.poll()
        clock.time += 1_000L
        client.responses["/v5/execution/list"] = page(BYBIT_DOC_FUNDING)

        funding.poll()

        assertThat(fundingReads()).hasSize(2)
        assertThat(fundingReads().last()).contains("startTime=${clock.now() - FUNDING_REPLAY_MS}")
        assertThat(charged).hasSize(1)
    }

    companion object {
        /** The `execType=Funding` record of Bybit's own response example (docs/v5/pre-upgrade/execution). */
        const val BYBIT_DOC_FUNDING =
            """{"symbol":"BTCUSDT","orderId":"1682553600-BTCUSDT-592334-Sell","orderLinkId":"","side":"Sell",""" +
                """"orderPrice":"0.00","orderQty":"0.000","leavesQty":"0.000","orderType":"UNKNOWN",""" +
                """"stopOrderType":"UNKNOWN","execFee":"0.6364003","execId":"11f1c4ed-ff20-4d73-acb7-96e43a917f25",""" +
                """"execPrice":"28399.90","execQty":"0.011","execType":"Funding","execValue":"312.3989",""" +
                """"execTime":"1682553600000","isMaker":false,"feeRate":"0.00203714","tradeIv":"","markIv":"",""" +
                """"markPrice":"28399.90","indexPrice":"","underlyingPrice":"","blockTradeId":"","closedSize":"0.000"}"""
    }
}
