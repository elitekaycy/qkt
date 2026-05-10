package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * A confirmed fill produced by a broker.
 *
 * Trades are the canonical audit-trail record: every realized P&L change ultimately
 * derives from a `Trade`. The [orderId] is the client-assigned id that ties this fill
 * back to the originating [OrderRequest].
 */
data class Trade(
    val orderId: String,
    val symbol: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val side: Side,
    val timestamp: Long,
)
