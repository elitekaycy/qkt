package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * Builds a validated [Tick] from already-parsed optional fields, applying qkt's data-integrity
 * rules and deriving the canonical trade price (the bid/ask midpoint when no explicit price is
 * given). Both [CsvTickFeed] (string-parsed) and [BinaryTickFeed] (binary-decoded) route through
 * here, so the two feeds cannot diverge — this shared assembly is what keeps binary-backed
 * backtests bit-identical to CSV-backed ones.
 *
 * e.g. assemble("XAUUSD", ts, price=null, bid=1711.504, ask=1712.002, ...) ->
 *      Tick(price = mid = 1711.753, bid = 1711.504, ask = 1712.002, ...)
 *
 * [location] is a human-readable origin (e.g. "file.csv:42") used only in error messages.
 */
object TickAssembler {
    fun assemble(
        symbol: String,
        timestamp: Long,
        price: BigDecimal?,
        volume: BigDecimal?,
        bid: BigDecimal?,
        ask: BigDecimal?,
        bidVolume: BigDecimal?,
        askVolume: BigDecimal?,
        location: String,
    ): Tick {
        check(symbol.isNotEmpty()) { "$location: empty symbol" }
        check(price != null || (bid != null && ask != null)) {
            "$location: row needs price OR (bid AND ask)"
        }
        if (bid != null && ask != null) {
            check(bid <= ask) { "$location: bid > ask: bid=$bid, ask=$ask" }
        }
        // Inline per-field sign checks. The previous `listOf("name" to v, ...).forEach` allocated six
        // Pairs and a list on every tick purely to name the field in an error that only fires on
        // negative data — pure hot-path waste. Same checks, same messages.
        if (price != null && price.signum() < 0) error("$location: negative price: $price")
        if (bid != null && bid.signum() < 0) error("$location: negative bid: $bid")
        if (ask != null && ask.signum() < 0) error("$location: negative ask: $ask")
        if (volume != null && volume.signum() < 0) error("$location: negative volume: $volume")
        if (bidVolume != null && bidVolume.signum() < 0) error("$location: negative bidVolume: $bidVolume")
        if (askVolume != null && askVolume.signum() < 0) error("$location: negative askVolume: $askVolume")
        val finalPrice =
            price
                ?: bid!!
                    .add(ask!!, Money.CONTEXT)
                    .divide(BigDecimal(2), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
        return Tick(
            symbol = symbol,
            price = finalPrice,
            timestamp = timestamp,
            volume = volume,
            bid = bid,
            ask = ask,
            bidVolume = bidVolume,
            askVolume = askVolume,
        )
    }
}
