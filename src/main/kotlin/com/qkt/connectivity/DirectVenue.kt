package com.qkt.connectivity

import com.qkt.candles.TimeWindow
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * One account's venue spoken to directly, one request at a time, with no engine in between: what a
 * `qkt bot` one-shot command uses. A connector offers it through [TradingAccount.directVenue]; nothing
 * outside the connector knows which venue answers. Reads return null when the venue read failed (unknown,
 * never empty); mutations return a [DirectResult] whose [DirectResult.ok] is the venue's own verdict.
 */
interface DirectVenue {
    /** The venue's code for [qktSymbol] (`XAUUSDm`, `SOL_USDC-PERPETUAL`). */
    fun venueSymbol(qktSymbol: String): String

    /** The quote, account value and instrument limits an order is sized and priced against. */
    fun facts(qktSymbol: String): VenueFacts

    /**
     * Places [order]. [stopLoss]/[takeProfit] are absolute prices the venue attaches to the position, or
     * refused when it cannot hold them. [clientId] names the order at the venue where the venue takes one.
     */
    fun place(
        order: OrderRequest,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
        clientId: String,
    ): DirectResult

    /** Closes [position], all of it or [volume]. */
    fun close(
        position: DirectPosition,
        volume: BigDecimal?,
        clientId: String,
    ): DirectResult

    /** Sets [position]'s stop and target; a venue that holds neither refuses. */
    fun modify(
        position: DirectPosition,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
    ): DirectResult

    /** Cancels the working order [ticket]. */
    fun cancel(ticket: String): DirectResult

    fun account(): DirectAccount?

    fun positions(): List<DirectPosition>?

    fun pendingOrders(): List<DirectPendingOrder>?

    fun deals(
        fromMs: Long,
        toMs: Long,
    ): List<DirectDeal>?

    fun tick(qktSymbol: String): DirectTick?

    /** The newest [count] closed [window] bars of [qktSymbol] up to [nowMs], oldest first. */
    fun bars(
        qktSymbol: String,
        window: TimeWindow,
        count: Int,
        nowMs: Long,
    ): List<Candle>
}

/** Point-in-time facts for one symbol; a null field is one the venue did not report. */
data class VenueFacts(
    val bid: BigDecimal,
    val ask: BigDecimal,
    val equity: BigDecimal?,
    val balance: BigDecimal?,
    val accountCurrency: String?,
    /** Units of the underlying per 1.0 of quantity (100 for XAUUSD, 1 for a perpetual contract). */
    val contractSize: BigDecimal?,
    val quoteCurrency: String?,
    val volumeMin: BigDecimal?,
    val volumeStep: BigDecimal?,
    val volumeMax: BigDecimal?,
    val digits: Int?,
    /** The price grid; null when it is the last digit. */
    val tickSize: BigDecimal? = null,
    /** The venue's reference price (its mark), what price collars measure against; null: the mid. */
    val reference: BigDecimal? = null,
)

/**
 * A venue's answer to a mutation. [ticket] is the venue's order or position id (an MT5 ticket, a
 * gateway `client_order_id`); [deal] the execution id when one happened; [state] the order's state
 * where the venue reports one (`working`, `filled`, `cancelled`, `rejected`).
 */
data class DirectResult(
    val ok: Boolean,
    val retcode: Int,
    val ticket: String,
    val deal: String,
    val price: BigDecimal,
    val error: String?,
    val state: String? = null,
    val filledQuantity: BigDecimal? = null,
)

data class DirectAccount(
    val equity: BigDecimal,
    val balance: BigDecimal,
    val currency: String,
    val margin: BigDecimal?,
    val freeMargin: BigDecimal?,
    val marginLevel: BigDecimal?,
    val openProfit: BigDecimal?,
    val leverage: Long?,
    val login: String,
    val server: String,
    val hedging: Boolean,
)

/** One open position; [symbol] is the venue's code. */
data class DirectPosition(
    val ticket: String,
    val symbol: String,
    val side: String,
    val lots: BigDecimal,
    val entry: BigDecimal,
    val current: BigDecimal?,
    val sl: BigDecimal?,
    val tp: BigDecimal?,
    val profit: BigDecimal?,
    val swap: BigDecimal?,
    val openedAtMs: Long?,
    val comment: String?,
)

data class DirectPendingOrder(
    val ticket: String,
    val symbol: String,
    val type: String,
    val lots: BigDecimal,
    val price: BigDecimal?,
    val sl: BigDecimal?,
    val tp: BigDecimal?,
    val expiresAt: Long?,
)

data class DirectDeal(
    val dealTicket: String,
    val positionTicket: String?,
    val symbol: String,
    val side: String,
    val entry: String?,
    val lots: BigDecimal,
    val price: BigDecimal,
    val profit: BigDecimal?,
    val commission: BigDecimal?,
    val swap: BigDecimal?,
    val fee: BigDecimal?,
    val timeMs: Long,
    val comment: String?,
)

data class DirectTick(
    val bid: BigDecimal,
    val ask: BigDecimal,
    val timeMs: Long,
)
