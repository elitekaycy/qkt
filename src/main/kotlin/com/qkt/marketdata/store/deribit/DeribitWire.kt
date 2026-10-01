package com.qkt.marketdata.store.deribit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A JSON-RPC response from Deribit: its [result], or the venue's [error]. */
@Serializable
internal data class DeribitEnvelope<T>(
    val result: T? = null,
    val error: DeribitError? = null,
)

/** A JSON-RPC error as Deribit reports it (with HTTP 400), and the reason it may add. */
@Serializable
internal data class DeribitError(
    val code: Int = 0,
    val message: String = "",
    val data: DeribitErrorData? = null,
) {
    /** The message with its reason, e.g. `Invalid params (invalid index)`. */
    val description: String get() = data?.reason?.let { "$message ($it)" } ?: message
}

/** The detail Deribit attaches to some errors. */
@Serializable
internal data class DeribitErrorData(
    val reason: String? = null,
)

/**
 * The fields of a Deribit instrument the option catalog reads; numbers are kept as their JSON literals.
 * The premium currency is [counterCurrency]: the history host omits `instrument_type` and reports the
 * base coin as `quote_currency` for expired linear options, while `counter_currency` holds on both hosts.
 */
@Serializable
internal data class DeribitInstrument(
    @SerialName("instrument_name") val name: String,
    @SerialName("instrument_type") val type: String? = null,
    @SerialName("settlement_currency") val settlementCurrency: String? = null,
    @SerialName("counter_currency") val counterCurrency: String? = null,
    val strike: JsonPrimitive? = null,
    @SerialName("option_type") val optionType: String? = null,
    @SerialName("expiration_timestamp") val expiryMs: Long? = null,
)

/** One page of `get_delivery_prices`. */
@Serializable
internal data class DeribitDeliveryPage(
    val data: List<DeribitDelivery> = emptyList(),
    @SerialName("records_total") val total: Int = 0,
)

/** One day's delivery price of an index, kept as its JSON literal. */
@Serializable
internal data class DeribitDelivery(
    val date: String,
    @SerialName("delivery_price") val price: JsonPrimitive,
)
