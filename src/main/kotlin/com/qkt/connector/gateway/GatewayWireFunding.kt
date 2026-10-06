package com.qkt.connector.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The VGP v1 funding objects (docs/superpowers/specs/2026-10-01-vgp-v1-wire.md): what a venue charged an
// account for holding a perpetual, and the public rate history a backtest replays. Amounts stay decimal
// strings here.

/**
 * A `funding` event: the venue charged ([amount] positive) or credited ([amount] negative) the account on
 * perpetual [symbol] at [time]. [position] is the signed quantity it charged on, null when the venue does
 * not say; [fundingId] is unique per venue record.
 */
@Serializable
data class WireFunding(
    @SerialName("funding_id") val fundingId: String,
    val symbol: String,
    val amount: String,
    val currency: String,
    val position: String? = null,
    val time: Long,
)

/** `GET /v1/funding`. */
@Serializable
data class WireFundings(
    val funding: List<WireFunding>,
)

/** One published funding rate: a unit long held through [time] pays `rate × price`; [price] null when unpublished. */
@Serializable
data class WireFundingRate(
    val time: Long,
    val rate: String,
    val price: String? = null,
)

/** `GET /v1/funding-rates`: one page of [rates], oldest first; [next] is the `from` of the next page, null on the last. */
@Serializable
data class WireFundingRates(
    val rates: List<WireFundingRate>,
    val next: Long? = null,
)

/** The capability a gateway declares when it reports the funding it charges on perpetuals. */
const val FUNDING_CAPABILITY = "funding"
