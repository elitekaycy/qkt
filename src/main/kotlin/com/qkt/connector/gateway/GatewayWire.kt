package com.qkt.connector.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The VGP v1 wire objects (docs/superpowers/specs/2026-10-01-vgp-v1-wire.md). Money and quantities
// stay decimal strings here; GatewayEventTranslator parses them into exact BigDecimals.

/** Which symbols the gateway's kill switch covers: every one, or [symbols]. */
@Serializable
data class WireKillSwitch(
    val all: Boolean,
    val symbols: List<String> = emptyList(),
)

/** `GET /v1/health`: the gateway's protocol, adapter and account identity, and its venue link. */
@Serializable
data class WireHealth(
    val protocol: String,
    val adapter: String,
    @SerialName("adapter_version") val adapterVersion: String,
    @SerialName("account_login") val accountLogin: String,
    @SerialName("trade_mode") val tradeMode: String,
    @SerialName("venue_connected") val venueConnected: Boolean,
    @SerialName("kill_switch") val killSwitch: WireKillSwitch,
    @SerialName("server_time") val serverTime: Long,
    val stream: String,
    val seq: Long,
)

/** `GET /v1/account` and the `account` event. */
@Serializable
data class WireAccount(
    val currency: String,
    val balance: String,
    val equity: String,
    @SerialName("margin_used") val marginUsed: String,
    @SerialName("margin_available") val marginAvailable: String,
    @SerialName("initial_margin") val initialMargin: String? = null,
    @SerialName("maintenance_margin") val maintenanceMargin: String? = null,
)

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
)

/** One position: per symbol on a netting account, per [ticket] on a hedging one. */
@Serializable
data class WirePosition(
    val symbol: String,
    val quantity: String,
    @SerialName("avg_price") val avgPrice: String,
    val ticket: String? = null,
    @SerialName("opened_at") val openedAt: Long? = null,
)

/** `GET /v1/positions`. */
@Serializable
data class WirePositions(
    val accounting: String,
    val positions: List<WirePosition>,
)

/** An order as the gateway reports it; [filledQuantity] is cumulative. */
@Serializable
data class WireOrder(
    @SerialName("client_order_id") val clientOrderId: String,
    @SerialName("venue_order_id") val venueOrderId: String? = null,
    val symbol: String,
    val side: String,
    val type: String,
    val quantity: String,
    @SerialName("limit_price") val limitPrice: String? = null,
    @SerialName("stop_price") val stopPrice: String? = null,
    @SerialName("time_in_force") val timeInForce: String,
    @SerialName("reduce_only") val reduceOnly: Boolean = false,
    val status: String,
    @SerialName("filled_quantity") val filledQuantity: String = "0",
    @SerialName("avg_fill_price") val avgFillPrice: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

/** `GET /v1/orders`. */
@Serializable
data class WireOrders(
    val orders: List<WireOrder>,
)

/** The body of `POST /v1/orders`: the fields a client sets. */
@Serializable
data class WireSubmit(
    @SerialName("client_order_id") val clientOrderId: String,
    val symbol: String,
    val side: String,
    val type: String,
    val quantity: String,
    @SerialName("limit_price") val limitPrice: String? = null,
    @SerialName("stop_price") val stopPrice: String? = null,
    @SerialName("time_in_force") val timeInForce: String,
    @SerialName("reduce_only") val reduceOnly: Boolean = false,
)

/** One typed venue cost of a fill, in [currency]. */
@Serializable
data class WireCost(
    val kind: String,
    val amount: String,
    val currency: String,
)

/** One venue execution; [fillId] is unique per execution, so a replayed fill is recognized. */
@Serializable
data class WireFill(
    @SerialName("client_order_id") val clientOrderId: String,
    @SerialName("venue_order_id") val venueOrderId: String? = null,
    @SerialName("fill_id") val fillId: String,
    val symbol: String,
    val side: String,
    val quantity: String,
    val price: String,
    val time: Long,
    val costs: List<WireCost> = emptyList(),
)

/** A `settlement` event: expiring [symbol] settled at [price] per unit, with the costs the venue charged the account. */
@Serializable
data class WireSettlement(
    val symbol: String,
    val price: String,
    val time: Long,
    val costs: List<WireCost> = emptyList(),
)

/** `GET /v1/deals`. */
@Serializable
data class WireDeals(
    val deals: List<WireFill>,
)

/** A quote event; null fields are sides or values the venue did not report. */
@Serializable
data class WireQuote(
    val symbol: String,
    val bid: String? = null,
    val ask: String? = null,
    @SerialName("bid_size") val bidSize: String? = null,
    @SerialName("ask_size") val askSize: String? = null,
    val mark: String? = null,
    @SerialName("mark_iv") val markIv: String? = null,
    val underlying: String? = null,
    val time: Long,
)

/** One stream message: [type] names what [data] holds; a `reset` carries no data. */
@Serializable
data class WireEvent(
    val stream: String,
    val seq: Long,
    val type: String,
    val time: Long? = null,
    val data: JsonElement? = null,
)

/** `GET /v1/settlements`. */
@Serializable
data class WireSettlements(
    val settlements: List<WireSettlement>,
)

/** The `error` object of a non-2xx response. */
@Serializable
data class WireError(
    val code: String,
    val message: String,
)

/** A non-2xx response body. */
@Serializable
data class WireErrorBody(
    val error: WireError,
)
