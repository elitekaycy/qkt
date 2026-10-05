package com.qkt.instrument

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * One listed contract of a futures root: its venue symbol without the venue prefix, its expiry in
 * UTC epoch millis and, once settled, its delivery price as an exact decimal string. A catalog built
 * from a vendor archive may also give how much the contract traded over its life ([lifetimeVolume])
 * and its highest open interest ([peakOpenInterest]), as decimal strings; continuous chains use them
 * to skip delivery months that are listed but never traded.
 */
@Serializable
data class ListedContract(
    val symbol: String,
    val expiryMs: Long,
    val deliveryPrice: String? = null,
    val lifetimeVolume: String? = null,
    val peakOpenInterest: String? = null,
) {
    init {
        requireNonNegative("lifetimeVolume", lifetimeVolume)
        requireNonNegative("peakOpenInterest", peakOpenInterest)
        require(symbol.isNotBlank()) { "ListedContract.symbol must not be blank" }
        require(expiryMs > 0) { "ListedContract.expiryMs must be > 0: $expiryMs" }
        require(deliveryPrice == null || (deliveryPrice.toBigDecimalOrNull()?.signum() ?: 0) > 0) {
            "ListedContract.deliveryPrice must be > 0: $deliveryPrice"
        }
    }

    /** The delivery price, or null before the contract has settled. */
    fun deliveryPriceOrNull(): BigDecimal? = deliveryPrice?.let(::BigDecimal)

    /** [lifetimeVolume] as a number, or null when the catalog does not give it. */
    fun lifetimeVolumeOrNull(): BigDecimal? = lifetimeVolume?.let(::BigDecimal)

    /** [peakOpenInterest] as a number, or null when the catalog does not give it. */
    fun peakOpenInterestOrNull(): BigDecimal? = peakOpenInterest?.let(::BigDecimal)

    private fun requireNonNegative(
        field: String,
        value: String?,
    ) = require(value == null || (value.toBigDecimalOrNull()?.signum() ?: -1) >= 0) {
        "ListedContract.$field must be a number >= 0: $value"
    }
}

/**
 * Every known contract of one root, in expiry order. Written by `qkt fetch --catalog` (or by hand for
 * a venue it has no source for); read by [ContractCatalogRegistry]. Adding a contract never needs a YAML edit.
 */
@Serializable
data class ContractCatalog(
    val root: String,
    val contracts: List<ListedContract>,
) {
    init {
        val duplicate = contracts.groupBy { it.symbol }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "ContractCatalog $root: duplicate contract '${duplicate?.key}'" }
    }

    /** The same catalog with [contracts] sorted by expiry; the form stored and compared. */
    fun sorted(): ContractCatalog = copy(contracts = contracts.sortedBy { it.expiryMs })
}
