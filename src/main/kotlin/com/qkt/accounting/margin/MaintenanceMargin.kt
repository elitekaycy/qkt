package com.qkt.accounting.margin

import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * The maintenance margin of the positions an account holds whose roots declare margin terms (futures,
 * and options whose root declares them): [MarginModel.maintenance] at each one's last price, else its
 * entry price. An option root without terms is not counted: its requirement ([OptionMargin]) is a
 * cash-secured worst case for opening, which an account may sit below while it unwinds a structure leg
 * by leg, not a maintenance level. Whether a symbol is margined is looked up once per symbol.
 */
class MaintenanceMargin(
    private val margin: MarginModel,
    private val prices: MarketPriceProvider,
    private val positions: PositionProvider,
) {
    private val margined = HashMap<String, Boolean>()

    /** Whether [symbol]'s root declares margin terms. */
    fun isMargined(symbol: String): Boolean = margined.getOrPut(symbol) { margin.hasTerms(symbol) }

    /** Whether the account holds any margined position. */
    fun holdsAny(): Boolean {
        for (symbol in positions.symbols()) {
            if (isMargined(symbol) && isOpen(symbol)) return true
        }
        return false
    }

    /** The margined symbols the account holds, sorted. */
    fun heldSymbols(): List<String> = positions.symbols().filter { isMargined(it) && isOpen(it) }.sorted()

    /** The total maintenance margin, in account currency, of the margined positions held at [nowMs]. */
    fun total(nowMs: Long): BigDecimal {
        var total = BigDecimal.ZERO
        for (symbol in positions.symbols()) {
            if (!isMargined(symbol)) continue
            val position = positions.positionFor(symbol)?.takeIf { it.quantity.signum() != 0 } ?: continue
            val price = prices.lastPrice(symbol) ?: position.avgEntryPrice
            total = total.add(margin.maintenance(symbol, position.quantity, price, nowMs))
        }
        return total
    }

    private fun isOpen(symbol: String): Boolean =
        positions
            .positionFor(symbol)
            ?.quantity
            ?.signum()
            ?.let { it != 0 } == true
}
