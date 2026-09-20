package com.qkt.parity.mt5golden

import kotlinx.serialization.Serializable

@Serializable
internal data class CapturedTick(
    val sequence: Long,
    val timestampMs: Long,
    val symbol: String,
    val bid: String,
    val ask: String,
)

@Serializable
internal data class CapturedSubmission(
    val sequence: Long,
    val clientOrder: CapturedClientOrder,
    val venueRequest: CapturedVenueRequest,
)

@Serializable
internal enum class CapturedOrderKind {
    MARKET,
    LIMIT,
    STOP,
    STOP_LIMIT,
}

@Serializable
internal data class CapturedClientOrder(
    val id: String,
    val strategyId: String,
    val symbol: String,
    val side: String,
    val kind: CapturedOrderKind,
    val quantity: String,
    val timeInForce: String,
    val timestampMs: Long,
    val expiresAtMs: Long? = null,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
)

@Serializable
internal data class CapturedVenueRequest(
    val clientOrderId: String,
    val symbol: String,
    val action: String,
    val orderType: String,
    val volume: String,
    val price: String? = null,
    val stopLoss: String? = null,
    val takeProfit: String? = null,
    val magic: Long,
    val typeTime: String,
    val expirationMs: Long? = null,
)

@Serializable
internal data class CapturedVenueOrder(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val accepted: Boolean,
    val retcode: Int,
    val status: String,
    val comment: String,
)

@Serializable
internal data class CapturedVenueDeal(
    val clientOrderId: String,
    val dealId: String,
    val positionTicket: String,
    val side: String,
    val executedVolume: String,
    val price: String,
    val retcode: Int,
    val commission: String,
    val swap: String,
    val fee: String,
)
