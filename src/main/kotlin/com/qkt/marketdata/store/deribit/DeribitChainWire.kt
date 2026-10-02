package com.qkt.marketdata.store.deribit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** One row of `get_book_summary_by_currency`: an option's book, mark, forward and index at [createdMs]. */
@Serializable
internal data class DeribitBookRow(
    @SerialName("instrument_name") val name: String,
    @SerialName("bid_price") val bid: JsonPrimitive? = null,
    @SerialName("ask_price") val ask: JsonPrimitive? = null,
    @SerialName("mark_price") val mark: JsonPrimitive,
    @SerialName("mark_iv") val markIv: JsonPrimitive? = null,
    @SerialName("underlying_price") val underlying: JsonPrimitive,
    @SerialName("interest_rate") val rate: JsonPrimitive? = null,
    @SerialName("creation_timestamp") val createdMs: Long,
    @SerialName("estimated_delivery_price") val index: JsonPrimitive? = null,
)

/** One page of `get_last_trades_by_currency_and_time`; both fields are required, so a short answer fails. */
@Serializable
internal data class DeribitTradePage(
    val trades: List<DeribitTrade>,
    @SerialName("has_more") val hasMore: Boolean,
)

/** The fields of a Deribit option trade a chain uses; decimals stay JSON literals until read exactly. */
@Serializable
internal data class DeribitTrade(
    @SerialName("trade_id") val id: String,
    @SerialName("trade_seq") val seq: Long,
    val timestamp: Long,
    @SerialName("instrument_name") val name: String,
    @SerialName("mark_price") val mark: JsonPrimitive,
    val iv: JsonPrimitive? = null,
    @SerialName("index_price") val index: JsonPrimitive,
)
