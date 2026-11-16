package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.instrument.FamilyDirectory
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.marks.MarkPrices
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Serves event slots (`VENUE:FAMILY@<event>.<strike>`, [SlotSymbol]) from the per-contract quote ticks of [inner]: a
 * slot's ticks are those of the contract it follows ([SlotTicks]), re-picked at each close of the stream's window and
 * never back-adjusted. The first slot of each family in [declared] also carries the family's contract ticks, stamped
 * with their own symbols, so the contracts a slot trades are priced and settle; a contract [declared] explicitly keeps
 * its own feed instead. Which contract a slot followed is remembered for [slotContractAt], where orders and the
 * slot's event fields resolve. Every other symbol is served by [inner] unchanged.
 *
 * A slot has no bars of its own (warmup starts cold), no tick slices (`--tick-fills`) and no live feed yet.
 */
class SlotMarketSource(
    private val inner: MarketSource,
    instruments: InstrumentRegistry,
    declared: List<String>,
) : MarketSource {
    private val families = FamilyDirectory(instruments)
    private val explicit = declared.toSet()
    private val slots = declared.mapNotNull { SlotSymbol.parse(it) }
    private val owners = slots.distinctBy { it.root }.map { it.symbol }.toSet()
    private val follows = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, String>>()

    init {
        for (slot in slots) {
            require(families.isFamily(slot.root)) {
                "${slot.symbol}: ${slot.root} is not an event family declared under events: in instruments.yaml"
            }
        }
    }

    override val name: String get() = inner.name
    override val capabilities: Set<MarketSourceCapability> get() = inner.capabilities

    override fun supports(symbol: String): Boolean = SlotSymbol.isSlot(symbol) || inner.supports(symbol)

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        if (SlotSymbol.isSlot(symbol)) setOf(MarketSourceCapability.TICKS) else inner.capabilitiesFor(symbol)

    override fun tickDeliveryLagMsFor(symbol: String): Long = inner.tickDeliveryLagMsFor(symbol)

    /** A slot follows several contracts in turn, so it has no mark, option mark or tape of its own. */
    override fun marksFor(symbol: String): MarkPrices? = if (SlotSymbol.isSlot(symbol)) null else inner.marksFor(symbol)

    override fun optionMarksFor(symbol: String): OptionMarks? =
        if (SlotSymbol.isSlot(symbol)) null else inner.optionMarksFor(symbol)

    override fun tradeFlowFor(symbol: String): TradeFlow? =
        if (SlotSymbol.isSlot(symbol)) null else inner.tradeFlowFor(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed {
        val live = symbols.filter { SlotSymbol.isSlot(it) }
        require(live.isEmpty()) { "event slots $live are served in backtests only; live slots are not wired yet" }
        return inner.liveTicks(symbols)
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = if (SlotSymbol.isSlot(symbol)) emptySequence() else inner.bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = sampledTicks(symbol, null, range)

    /** A slot re-picks at each close of [window]; a reader naming none (warmup, a probe) re-picks every minute. */
    override fun sampledTicks(
        symbol: String,
        window: TimeWindow?,
        range: TimeRange,
    ): Sequence<Tick> {
        val slot = SlotSymbol.parse(symbol) ?: return inner.sampledTicks(symbol, window, range)
        val picks = follows.computeIfAbsent(symbol) { ConcurrentSkipListMap() }
        val passes = { contract: String -> symbol in owners && contract !in explicit }
        val contracts = families.contracts(slot.root)
        return SlotTicks(slot, window?.durationMs ?: MINUTE_MS, contracts, passes) { at, contract ->
            picks[at] = contract.orEmpty()
        }.over(FamilyTicks.merged(inner, contracts, range))
    }

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        require(!SlotSymbol.isSlot(symbol)) {
            "tick-resolved fills (--tick-fills) are not supported for event slots ($symbol)"
        }
        return inner.tickSlice(symbol, fromMs, toMs)
    }

    override fun slotContractAt(
        symbol: String,
        atMs: Long,
    ): String? =
        if (SlotSymbol.isSlot(symbol)) {
            follows[symbol]?.floorEntry(atMs - 1)?.value?.takeIf { it.isNotEmpty() }
        } else {
            inner.slotContractAt(symbol, atMs)
        }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
