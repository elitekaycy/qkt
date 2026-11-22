package com.qkt.marketdata.store.deribit

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class DeribitChainWireSerializationTest {
    private val rowBody =
        """{"instrument_name":"BTC-1","mark_price":0.1,"underlying_price":90000,"creation_timestamp":5}"""
    private val tradeBody =
        """{"trade_id":"t","trade_seq":1,"timestamp":2,"instrument_name":"BTC-1","mark_price":0.1,"index_price":9}"""

    private fun p(v: Number) = JsonPrimitive(v)

    @Test
    fun `book row round trips with every optional set and none`() {
        roundTrips(DeribitBookRow("BTC-1", p(1), p(2), p(3), p(4), p(5), p(6), 7L, p(8)))
        roundTrips(DeribitBookRow("BTC-1", mark = p(3), underlying = p(5), createdMs = 7L))
        decodes(rowBody, DeribitBookRow("BTC-1", null, null, p(0.1), null, p(90000), null, 5L, null))
        refusesWithout<DeribitBookRow>(
            rowBody,
            "instrument_name",
            "mark_price",
            "underlying_price",
            "creation_timestamp",
        )
        refusesWrongType<DeribitBookRow>(
            rowBody,
            "bid_price",
            "ask_price",
            "mark_iv",
            "interest_rate",
            "estimated_delivery_price",
        )
        refusesWrongType<DeribitBookRow>(rowBody, "creation_timestamp", "instrument_name")
    }

    @Test
    fun `trade round trips with and without iv`() {
        val full = DeribitTrade("t", 1L, 2L, "BTC-1", p(3), p(4), p(5))
        val bare = DeribitTrade("t", 1L, 2L, "BTC-1", p(3), null, p(5))
        roundTrips(full)
        roundTrips(bare)
        decodes(tradeBody, DeribitTrade("t", 1L, 2L, "BTC-1", p(0.1), null, p(9)))
        refusesWithout<DeribitTrade>(
            tradeBody,
            "trade_id",
            "trade_seq",
            "timestamp",
            "instrument_name",
            "mark_price",
            "index_price",
        )
        refusesWrongType<DeribitTrade>(tradeBody, "iv", "trade_seq", "timestamp")
    }

    @Test
    fun `trade page needs both its fields`() {
        val trade = DeribitTrade("t", 1L, 2L, "BTC-1", p(3), null, p(5))
        roundTrips(DeribitTradePage(listOf(trade), true))
        roundTrips(DeribitTradePage(emptyList(), false))
        refusesWithout<DeribitTradePage>("""{"trades":[],"has_more":false}""", "trades", "has_more")
        refusesWrongType<DeribitTradePage>("""{"trades":[],"has_more":false}""", "has_more", "trades")
    }
}
