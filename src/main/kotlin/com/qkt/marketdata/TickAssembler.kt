package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

/** A structurally readable quote whose bid exceeds its ask. Feeds may drop this vendor anomaly. */
class CrossedQuoteException(
    message: String,
) : IllegalStateException(message)

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
 * [location] is a lazy supplier of a human-readable origin (e.g. "file.csv:42"), invoked only when a
 * validation check fails — so the hot path never builds the string for the valid-data case.
 */
object TickAssembler {
    // Mid-price divisor, hoisted: every quote-only tick (all of dukascopy) derives a mid, and
    // constructing the divisor per tick was pure allocation.
    private val TWO = BigDecimal(2)

    fun assemble(
        symbol: String,
        timestamp: Long,
        price: BigDecimal?,
        volume: BigDecimal?,
        bid: BigDecimal?,
        ask: BigDecimal?,
        bidVolume: BigDecimal?,
        askVolume: BigDecimal?,
        location: () -> String,
    ): Tick {
        check(symbol.isNotEmpty()) { "${location()}: empty symbol" }
        check(price != null || (bid != null && ask != null)) {
            "${location()}: row needs price OR (bid AND ask)"
        }
        if (bid != null && ask != null) {
            if (bid > ask) {
                throw CrossedQuoteException("${location()}: bid > ask: bid=$bid, ask=$ask")
            }
        }
        // Inline per-field sign checks. The previous `listOf("name" to v, ...).forEach` allocated six
        // Pairs and a list on every tick purely to name the field in an error that only fires on
        // negative data — pure hot-path waste. Same checks, same messages.
        if (price != null && price.signum() < 0) error("${location()}: negative price: $price")
        if (bid != null && bid.signum() < 0) error("${location()}: negative bid: $bid")
        if (ask != null && ask.signum() < 0) error("${location()}: negative ask: $ask")
        if (volume != null && volume.signum() < 0) error("${location()}: negative volume: $volume")
        if (bidVolume != null && bidVolume.signum() < 0) error("${location()}: negative bidVolume: $bidVolume")
        if (askVolume != null && askVolume.signum() < 0) error("${location()}: negative askVolume: $askVolume")
        val finalPrice =
            price
                ?: bid!!
                    .add(ask!!, Money.CONTEXT)
                    .divide(TWO, Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
        // Older Dukascopy cache rows left aggregate volume blank even though both side volumes
        // were stored. Derive it at read time so existing datasets gain the same capability as
        // newly fetched rows without requiring a destructive backfill.
        val finalVolume =
            volume
                ?: if (bidVolume != null && askVolume != null) {
                    bidVolume.add(askVolume, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
                } else {
                    null
                }
        return Tick(
            symbol = symbol,
            price = finalPrice,
            timestamp = timestamp,
            volume = finalVolume,
            bid = bid,
            ask = ask,
            bidVolume = bidVolume,
            askVolume = askVolume,
        )
    }
}
