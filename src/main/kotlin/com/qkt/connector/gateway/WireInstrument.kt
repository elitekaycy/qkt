package com.qkt.connector.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One instrument the gateway lists (`GET /v1/instruments`); [expiry] only for dated contracts,
 * [strike]/[right]/[underlying] only for options. The fields after [trading] are an event contract's terms
 * (`kind: event`), absent for every other kind; enum values are lowercase names (`greater_or_equal`, `probability`).
 * [eventSpec] reads them.
 */
@Serializable
data class WireInstrument(
    val code: String,
    val kind: String,
    val currency: String,
    @SerialName("contract_size") val contractSize: String,
    @SerialName("tick_size") val tickSize: String,
    @SerialName("volume_step") val volumeStep: String,
    @SerialName("volume_min") val volumeMin: String,
    val expiry: Long? = null,
    val strike: String? = null,
    val right: String? = null,
    val underlying: String? = null,
    /** The venue's grouping of the instrument (`us_equity`), absent when it has none. */
    val category: String? = null,
    /** `long_only`, `short_only`, `close_only` or `disabled`; absent means fully tradable. */
    val trading: String? = null,
    /** The recurring question an event contract belongs to (its family). */
    val series: String? = null,
    /** One instance of the series: one close time, one game, one race. */
    val event: String? = null,
    /** `greater`, `greater_or_equal`, `less`, `less_or_equal`, `between`, `custom` or `none`. */
    @SerialName("strike_type") val strikeType: String? = null,
    val floor: String? = null,
    val cap: String? = null,
    @SerialName("open_at") val openAt: Long? = null,
    @SerialName("close_at") val closeAt: Long? = null,
    @SerialName("occurs_at") val occursAt: Long? = null,
    val occasion: String? = null,
    @SerialName("can_close_early") val canCloseEarly: Boolean? = null,
    val exclusive: Boolean? = null,
    val frequency: String? = null,
    val title: String? = null,
    /** The settled value once known, in [0, 1]. */
    val outcome: String? = null,
    /** The per-order fee shape; `probability` is rate × contracts × p × (1 − p), rounded up to [feeRounding]. */
    @SerialName("fee_model") val feeModel: String? = null,
    @SerialName("taker_fee_rate") val takerFeeRate: String? = null,
    @SerialName("maker_fee_rate") val makerFeeRate: String? = null,
    @SerialName("fee_rounding") val feeRounding: String? = null,
)
