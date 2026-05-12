package com.qkt.broker.mt5

import java.math.BigDecimal

/** Bid/ask tick reported by the gateway. */
data class MT5Tick(
    val symbol: String,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val time: Long,
)

/** Account-level snapshot from the MT5 venue — used for equity and leverage tracking. */
data class MT5AccountInfo(
    val balance: BigDecimal,
    val equity: BigDecimal,
    val currency: String,
    val leverage: Int,
)

/** Open position on the venue, filtered by [MT5BrokerProfile.magic] during reconciliation. */
data class MT5Position(
    val ticket: Long,
    val symbol: String,
    val type: Int,
    val volume: BigDecimal,
    val priceOpen: BigDecimal,
    val sl: BigDecimal,
    val tp: BigDecimal,
    val profit: BigDecimal,
    val magic: Int,
    val openTime: Long,
    val comment: String? = null,
)

/**
 * Fields modifiable on a working order. Only non-null fields are sent.
 *
 * MT5's `OrderModify` accepts changes to the entry trigger price ([price]), SL/TP
 * absolute prices ([sl], [tp]), trailing distance ([slDistance], in MT5 points),
 * and pending expiry ([expiration]).
 */
data class MT5OrderModification(
    val price: BigDecimal? = null,
    val sl: BigDecimal? = null,
    val tp: BigDecimal? = null,
    val slDistance: Long? = null,
    val expiration: Long? = null,
)

/**
 * Pending (working) order reported by the gateway. Distinct from [MT5Position] which
 * tracks filled positions. The pending order is consumed when it triggers (becoming
 * a position) or expires/cancels (disappearing without becoming a position).
 *
 * Used by [MT5PendingOrderPoller] to detect external cancellations and GTD expiries
 * — events the gateway's position poller can't see.
 */
data class MT5PendingOrder(
    val ticket: Long,
    val symbol: String,
    val type: String,
    val volume: BigDecimal,
    val priceOpen: BigDecimal,
    val sl: BigDecimal,
    val tp: BigDecimal,
    val magic: Int,
    val timeSetup: Long,
    val timeExpiration: Long,
    val comment: String? = null,
)

/** Symbol metadata reported by the venue — used for size/price validation. */
data class MT5SymbolInfo(
    val ask: BigDecimal,
    val bid: BigDecimal,
    val digits: Int,
    val point: BigDecimal,
    val tradeStopsLevel: Int,
    val volumeMin: BigDecimal,
    val volumeStep: BigDecimal,
)

/**
 * JSON wire shape for `POST /order` to the gateway.
 *
 * [stopLimit] is set for `BUY_STOP_LIMIT`/`SELL_STOP_LIMIT` shapes — the limit price
 * that activates once [price] (the stop trigger) prints.
 *
 * [slDistance] is set for trailing stops — the distance in MT5 *points* that the
 * server-side trail follows the favorable price. Mutually exclusive with [sl]
 * (server manages the trail; clients shouldn't also set a fixed SL).
 */
data class MT5OrderRequest(
    val symbol: String,
    val volume: BigDecimal,
    val type: String,
    val price: BigDecimal? = null,
    val sl: BigDecimal? = null,
    val tp: BigDecimal? = null,
    val stopLimit: BigDecimal? = null,
    val slDistance: Long? = null,
    val deviation: Int = 20,
    val magic: Int,
    val comment: String,
    val expiration: Long? = null,
    val typeTime: String? = null,
)

/** Inner result block of a venue order response — `retcode` is the MQL5 trade return code. */
data class MT5OrderResult(
    val retcode: Int,
    val order: Long,
    val deal: Long,
    val price: BigDecimal,
    val comment: String,
)

/** Top-level response from `POST /order`. */
data class MT5OrderResponse(
    val result: MT5OrderResult,
    val errorMessage: String? = null,
)

/** MQL5 trade return code for a successful order (`TRADE_RETCODE_DONE`). */
const val MT5_TRADE_RETCODE_DONE: Int = 10009

/** Returns `true` iff [retcode] indicates a successful order placement. */
fun isOrderSuccessful(retcode: Int): Boolean = retcode == MT5_TRADE_RETCODE_DONE
