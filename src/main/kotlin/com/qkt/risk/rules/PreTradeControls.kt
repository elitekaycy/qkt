package com.qkt.risk.rules

import com.qkt.accounting.AccountingEngine
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
 * omission or percent-convention mix-up stops at this gate, not at the venue.
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
    ): List<RiskRule> =
        listOf(
            MaxOrderQty(maxOrderQty),
            MaxOrderNotional(maxOrderNotional, prices, instruments, accounting),
            PriceCollar(priceCollarFrac, prices),
        )
}
