package com.qkt.app

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import java.math.BigDecimal

/** A DERIBIT:BTC_USDC catalog (contract size 1) of four puts over two expiries, and leg orders on it. */
internal object StructureFixtures {
    /** 2026-10-09T08:00Z. */
    const val OCT9 = 1_791_532_800_000L

    /** 2026-10-30T08:00Z. */
    const val OCT30 = 1_793_347_200_000L
    const val P81 = "DERIBIT:BTC_USDC_9OCT26_81000_P"
    const val P78 = "DERIBIT:BTC_USDC_9OCT26_78000_P"
    const val P75 = "DERIBIT:BTC_USDC_9OCT26_75000_P"
    const val P80_30OCT = "DERIBIT:BTC_USDC_30OCT26_80000_P"

    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
        )

    val registry =
        OptionCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    OptionCatalog(
                        root.root,
                        listOf(
                            OptionListing("BTC_USDC-9OCT26-81000-P", "81000", "put", OCT9),
                            OptionListing("BTC_USDC-9OCT26-78000-P", "78000", "put", OCT9),
                            OptionListing("BTC_USDC-9OCT26-75000-P", "75000", "put", OCT9),
                            OptionListing("BTC_USDC-30OCT26-80000-P", "80000", "put", OCT30),
                        ),
                    ),
            ),
        )

    /** A market order [id] for 0.1 of [symbol] on [side] by strategy `st`. */
    fun market(
        id: String,
        symbol: String,
        side: Side,
        quantity: String = "0.1",
    ) = OrderRequest.Market(id, symbol, side, BigDecimal(quantity), TimeInForce.GTC, 0L, "st")
}
