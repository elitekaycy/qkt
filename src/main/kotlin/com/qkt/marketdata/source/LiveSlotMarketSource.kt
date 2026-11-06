package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.events.EventChainSymbol
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SlotSymbol
import com.qkt.instrument.TradeMode
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.marks.MarkPrices
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Serves a live session's event slots and event `CHAIN:` streams ([windows]: each one's window in ms) over [inner],
 * whose feed for them is their whole family's quotes stamped with each contract's own symbol (a gateway's `roots=`
 * subscription). Every inner tick passes through, so the contracts a slot trades are priced and settle; a slot then
 * repeats the ticks of the contract it follows ([SlotFollower], re-picked at each close of its window, as a backtest
 * picks), and a `CHAIN:` stream is valued after each quote of its family ([EventChainFollower]). The family is read
 * from [instruments] as it lists contracts now, so contracts listed after start are followed; one whose venue paused
 * it or closes it out (`trading` disabled or close only) is not picked. Which contract a slot followed is kept for
 * [slotContractAt], where its orders and event fields resolve. These streams have no bars (warmup starts cold), marks
 * or tape of their own; every other symbol is [inner]'s.
 */
class LiveSlotMarketSource(
    private val inner: MarketSource,
    private val instruments: InstrumentRegistry,
    private val windows: Map<String, Long>,
) : MarketSource {
    private val families = LiveFamilyContracts(instruments)
    private val follows = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, String>>()

    override val name: String get() = inner.name
    override val capabilities: Set<MarketSourceCapability> get() = inner.capabilities

    override fun supports(symbol: String): Boolean = symbol in windows || inner.supports(symbol)

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        if (symbol in windows) setOf(MarketSourceCapability.LIVE_TICKS) else inner.capabilitiesFor(symbol)

    override fun marksFor(symbol: String): MarkPrices? = if (symbol in windows) null else inner.marksFor(symbol)

    override fun optionMarksFor(symbol: String): OptionMarks? =
        if (symbol in windows) null else inner.optionMarksFor(symbol)

    override fun tradeFlowFor(symbol: String): TradeFlow? = if (symbol in windows) null else inner.tradeFlowFor(symbol)

    override fun tickDeliveryLagMsFor(symbol: String): Long = inner.tickDeliveryLagMsFor(symbol)

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = if (symbol in windows) emptySequence() else inner.bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = if (symbol in windows) emptySequence() else inner.ticks(symbol, range)

    override fun liveTicks(symbols: List<String>): TickFeed {
        val feed = inner.liveTicks(symbols)
        val steps = symbols.filter { it in windows }.map(::follower)
        return if (steps.isEmpty()) feed else FamilyTickFeed.over(feed, steps)
    }

    override fun slotContractAt(
        symbol: String,
        atMs: Long,
    ): String? =
        if (symbol in windows) {
            follows[symbol]?.floorEntry(atMs - 1)?.value?.takeIf { it.isNotEmpty() }
        } else {
            inner.slotContractAt(symbol, atMs)
        }

    private fun follower(symbol: String): (Tick) -> Tick? {
        val slot = SlotSymbol.parse(symbol)
        if (slot == null) {
            val root = EventChainSymbol.parse(symbol).root
            return EventChainFollower(symbol) { families.of(root) }::step
        }
        val picks = follows.computeIfAbsent(symbol) { ConcurrentSkipListMap() }
        val trading = { contract: String -> instruments.lookup(contract)?.tradeMode == TradeMode.FULL }
        return SlotFollower(slot, windows.getValue(symbol), { families.of(slot.root) }, trading) { at, contract ->
            picks[at] = contract.orEmpty()
            picks.headMap(at - KEPT_MS).clear()
        }::step
    }

    private companion object {
        /** How far back a slot's picks are kept for [slotContractAt]: an order resolves at the window it fired in. */
        const val KEPT_MS = 86_400_000L
    }
}
