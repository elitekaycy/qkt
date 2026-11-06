package com.qkt.connector.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One instrument the gateway lists; [expiry] only for dated contracts, [strike]/[right]/[underlying] only for options. */
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
)
