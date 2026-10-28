package com.qkt.risk.rules

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.margin.MarginModel
import com.qkt.accounting.margin.OptionMargin
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * The mandatory pre-trade control set (FIA §1.1/§1.4, SEC 15c3-5, MiFID II RTS 6):
 * per-order quantity cap, per-order notional cap, and a price collar. These are
 * ALWAYS on — they ship with defaults so "no limit configured" can never mean "no
 * limit". They are the backstop for the entire sizing-bug class: a 100x contractSize
 * omission or percent-convention mix-up stops at this gate, not at the venue. Given the account
 * [equity][standard], the set also refuses orders the account could not margin ([MarginRequirement];
 * it judges only instruments with margin terms, so it changes nothing for instruments without), orders a symbol's
 * venue [trade mode][VenueTradeMode] does not allow, and new positions on a share about to split ([SplitWindow]).
 * Given the venue's session answer, it refuses every order sent outside its symbol's session ([VenueSession]).
 */
object PreTradeControls {
    /** Default per-order quantity cap (lots/units). Generous; operators tighten per account. */
    val DEFAULT_MAX_ORDER_QTY: BigDecimal = BigDecimal("100")

    /** Default per-order notional cap in account currency. */
    val DEFAULT_MAX_ORDER_NOTIONAL: BigDecimal = BigDecimal("250000")

    /** Default price collar as a fraction (25%). Wide on purpose — a backstop, not a tuner. */
    val DEFAULT_PRICE_COLLAR_FRAC: BigDecimal = BigDecimal("0.25")

    fun standard(
        prices: MarketPriceProvider,
        instruments: InstrumentRegistry = NoopInstrumentRegistry,
        maxOrderQty: BigDecimal = DEFAULT_MAX_ORDER_QTY,
        maxOrderNotional: BigDecimal = DEFAULT_MAX_ORDER_NOTIONAL,
        priceCollarFrac: BigDecimal = DEFAULT_PRICE_COLLAR_FRAC,
        accounting: AccountingEngine = AccountingEngine(),
        equity: (() -> BigDecimal)? = null,
        session: ((symbol: String, atMs: Long) -> Boolean)? = null,
    ): List<RiskRule> =
        listOfNotNull(
            // First: an order the venue would refuse outright is refused for that reason, priced or not.
            VenueTradeMode(instruments),
            session?.let(::VenueSession),
            SplitWindow(instruments),
            MaxOrderQty(maxOrderQty),
            MaxOrderNotional(maxOrderNotional, prices, instruments, accounting),
            PriceCollar(priceCollarFrac, prices),
            equity?.let {
                val options = instruments.options()?.let { OptionMargin(instruments) }
                MarginRequirement(MarginModel(instruments, accounting), prices, options, it)
            },
        )
}
