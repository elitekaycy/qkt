package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.events.BrokerEvent
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.optionQuoteTick
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The hard limit's open loss on an option, on quotes as a session gets them: [optionQuoteTick], then the live gate's
 * rule that an option's price is not judged, then the price tracker. The position is 2 contracts of size 10.
 */
class SessionLimitMarksOptionTest {
    private val clock = FixedClock(1_800_000_000_000L)
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.TEN,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )
    private val venueName = "BTC_USDC-2OCT27-92000-C"
    private val symbol = "DERIBIT:BTC_USDC_2OCT27_92000_C"
    private val registry =
        OptionCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    OptionCatalog(
                        root.root,
                        listOf(OptionListing(venueName, "92000", "call", 1_822_500_000_000L)),
                        emptyMap(),
                    ),
            ),
        )
    private val positions = StrategyPositionTracker()
    private val prices = MarketPriceTracker()
    private val gate = MarketDataGate(clock, judgesOutliers = { judgesPriceOutliers(registry, it) })
    private val marks = SessionLimitMarks(positions, StrategyPnL(positions, prices, registry), prices, gate, registry)

    private fun holding(
        side: Side,
        entry: String,
    ) {
        val intents = IntentBook()
        intents.independentOpen("s", "c", "leg")
        intents.apply(
            positions,
            BrokerEvent.OrderFilled(
                clientOrderId = "c",
                brokerOrderId = "b",
                symbol = symbol,
                side = side,
                price = Money.of(entry),
                quantity = Money.of("2"),
                strategyId = "s",
                timestamp = 0L,
            ),
        )
    }

    private fun quote(
        mark: String,
        bid: String? = null,
        ask: String? = null,
    ) {
        val tick =
            optionQuoteTick(
                symbol,
                ChainQuote(
                    clock.now(),
                    venueName,
                    bid?.let(::BigDecimal),
                    ask?.let(::BigDecimal),
                    BigDecimal(mark),
                    null,
                    BigDecimal("83000"),
                    null,
                    0L,
                    QuoteSource.BOOK,
                ),
                root,
            )
        if (gate.observe(tick) == MarketDataGate.Verdict.OK) prices.update(gate.withoutAbsurdSides(tick))
    }

    private fun read() = marks.read(listOf("s"), Money.ZERO, StopFlatten.Book(emptyList(), false))

    private fun assertOpen(expected: String) {
        val reading = read()
        assertThat(reading.unmeasured).isEmpty()
        assertThat(reading.open).isEqualByComparingTo(expected)
    }

    @Test
    fun `a long is valued at the bid, not the mark, times the contract size`() {
        holding(Side.BUY, "1500")
        quote(mark = "1200", bid = "1100", ask = "1300")
        assertOpen("-8000")
    }

    @Test
    fun `a short is valued at the ask, not the mark, times the contract size`() {
        holding(Side.SELL, "1500")
        quote(mark = "1700", bid = "1600", ask = "1900")
        assertOpen("-8000")
    }

    @Test
    fun `a side far from the mark is an option's real book and is kept`() {
        holding(Side.BUY, "1500")
        quote(mark = "1000", bid = "300", ask = "2500")
        assertOpen("-24000")
    }

    @Test
    fun `a long with no bid is valued at the venue's mark and still counts`() {
        holding(Side.BUY, "1500")
        quote(mark = "1200", ask = "1300")
        assertOpen("-6000")
    }

    @Test
    fun `a premium collapsing between two quotes is a real loss, not an outlier`() {
        holding(Side.BUY, "1500")
        repeat(30) {
            clock.time += 1_000L
            quote(mark = "1500", bid = "1500", ask = "1505")
        }
        assertOpen("0")
        clock.time += 1_000L
        quote(mark = "200", bid = "200", ask = "205")
        assertOpen("-26000")
    }

    @Test
    fun `a worthless option marked at zero counts as the whole premium lost`() {
        holding(Side.BUY, "1500")
        quote(mark = "0")
        assertOpen("-30000")
    }

    @Test
    fun `a stale option quote counts as nothing and is named`() {
        holding(Side.BUY, "1500")
        quote(mark = "1200", bid = "1100", ask = "1300")
        clock.time += 3_600_000L
        val reading = read()
        assertThat(reading.open).isEqualByComparingTo("0")
        assertThat(reading.unmeasured).containsKey(symbol)
    }

    @Test
    fun `a held option with no quote yet counts as nothing and is named`() {
        holding(Side.BUY, "1500")
        assertThat(read().unmeasured[symbol]).isEqualTo("no price")
    }
}
