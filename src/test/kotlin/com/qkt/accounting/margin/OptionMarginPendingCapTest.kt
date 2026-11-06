package com.qkt.accounting.margin

import com.qkt.common.Side
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Every mix of pending orders is searched, so one expiry with too many pending option symbols is refused. */
class OptionMarginPendingCapTest {
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
    private val strikes = (0..OptionMargin.MAX_PENDING_SYMBOLS).map { 70_000 + it * 1_000 }
    private val registry =
        OptionCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    OptionCatalog(
                        root.root,
                        strikes.map { OptionListing("BTC_USDC-2OCT26-$it-P", "$it", "put", 1_790_928_000_000L) },
                    ),
            ),
        )

    private class PendingSells(
        private val symbols: Set<String>,
    ) : PositionProvider {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions(): Map<String, Position> = emptyMap()

        override fun pendingOrderQuantity(
            symbol: String,
            side: Side,
            strategyId: String?,
        ): BigDecimal = if (side == Side.SELL && symbol in symbols) BigDecimal("0.1") else BigDecimal.ZERO

        override fun pendingEntrySymbols(strategyId: String?): Set<String> = symbols
    }

    @Test
    fun `more pending option symbols on one expiry than can be searched are refused`() {
        val symbols = strikes.map { "DERIBIT:BTC_USDC_2OCT26_${it}_P" }.toSet()

        val outcome =
            OptionMargin(registry).required(symbols.first(), Side.BUY, BigDecimal("0.1"), PendingSells(symbols)) {
                BigDecimal("100")
            }

        assertThat((outcome as OptionMargin.Outcome.Refused).reason)
            .startsWith("too many pending option orders on ")
            .endsWith(" to margin")
    }
}
