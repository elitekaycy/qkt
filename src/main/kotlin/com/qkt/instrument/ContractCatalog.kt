package com.qkt.instrument

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * One listed contract of a futures root: its venue symbol without the venue prefix, its expiry in
 * UTC epoch millis and, once settled, its delivery price as an exact decimal string.
 */
@Serializable
data class ListedContract(
    val symbol: String,
    val expiryMs: Long,
    val deliveryPrice: String? = null,
) {
    init {
        require(symbol.isNotBlank()) { "ListedContract.symbol must not be blank" }
        require(expiryMs > 0) { "ListedContract.expiryMs must be > 0: $expiryMs" }
        require(deliveryPrice == null || (deliveryPrice.toBigDecimalOrNull()?.signum() ?: 0) > 0) {
            "ListedContract.deliveryPrice must be > 0: $deliveryPrice"
        }
    }

    /** The delivery price, or null before the contract has settled. */
    fun deliveryPriceOrNull(): BigDecimal? = deliveryPrice?.let(::BigDecimal)
}

/**
 * Every known contract of one root, in expiry order. Written by `qkt fetch` and by a live venue's
 * instrument list; read by [ContractCatalogRegistry]. Adding a contract never needs a YAML edit.
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
