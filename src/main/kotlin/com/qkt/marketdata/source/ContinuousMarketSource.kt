package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ChainSegment
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.time.Instant

/**
 * Serves continuous futures streams (`VENUE:ROOT@front`) from the per-contract data of [inner]: each
 * stretch of the stream reads the contract the roll schedule names, mapped onto the series by that
 * contract's forward adjustment and re-stamped with the continuous symbol. Explicit contracts are cut
 * at their expiry. Every other symbol is served by [inner] unchanged. Chains and price mappings are
 * resolved when a request is made, so a missing roll history fails before the run starts.
 */
class ContinuousMarketSource(
    private val inner: MarketSource,
    private val chains: ContinuousChains,
    private val instruments: InstrumentRegistry,
) : MarketSource {
    override val name: String get() = inner.name
    override val capabilities: Set<MarketSourceCapability> get() = inner.capabilities

    override fun supports(symbol: String): Boolean = chains.chainFor(symbol) != null || inner.supports(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed = inner.liveTicks(symbols)

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val chain =
            chains.chainFor(symbol) ?: return cutAtExpiry(symbol, inner.bars(symbol, window, range)) { it.startTime }
        requireBarsAvoidRolls(chain, window, range)
        return stitched(chain, range.from.toEpochMilli(), range.to.toEpochMilli()) { contract, seg ->
            inner.bars(contract, window, seg.range()).filter { it.startTime >= seg.fromMs && it.startTime < seg.toMs }
        }.flatMap { (space, candles) -> candles.map { it.inSpace(space, symbol) } }
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = tickSlice(symbol, range.from.toEpochMilli(), range.to.toEpochMilli())

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        val chain =
            chains.chainFor(symbol)
                ?: return cutAtExpiry(symbol, inner.tickSlice(symbol, fromMs, toMs)) { it.timestamp }
        return stitched(chain, fromMs, toMs) { contract, seg -> inner.tickSlice(contract, seg.fromMs, seg.toMs) }
            .flatMap { (space, ticks) -> ticks.map { it.inSpace(space, symbol) } }
    }

    /** Each segment's price mapping (resolved now, so gaps in the history fail at request time) and its data. */
    private fun <T> stitched(
        chain: ContinuousChain,
        fromMs: Long,
        toMs: Long,
        read: (contract: String, segment: ChainSegment) -> Sequence<T>,
    ): Sequence<Pair<PriceSpace, Sequence<T>>> =
        chain
            .segments(fromMs, toMs)
            .map { seg -> Triple(seg, chain.spaceFor(seg.index), chain.contractSymbol(seg.index)) }
            .asSequence()
            .map { (seg, space, contract) -> space to read(contract, seg) }

    private fun requireBarsAvoidRolls(
        chain: ContinuousChain,
        window: TimeWindow,
        range: TimeRange,
    ) {
        val inside =
            chain.schedule.transitions.firstOrNull {
                it.atMs > range.from.toEpochMilli() &&
                    it.atMs < range.to.toEpochMilli() &&
                    it.atMs % window.durationMs != 0L
            } ?: return
        error(
            "${chain.symbol} rolls at ${Instant.ofEpochMilli(inside.atMs)}, inside a ${window.durationMs} ms bar; " +
                "use a timeframe that divides the roll time or move roll.atUtc",
        )
    }

    private fun <T> cutAtExpiry(
        symbol: String,
        data: Sequence<T>,
        time: (T) -> Long,
    ): Sequence<T> {
        val expiry = (instruments.lookup(symbol)?.derivative as? FutureTerms)?.expiryMs ?: return data
        return data.filter { time(it) < expiry }
    }

    private fun ChainSegment.range(): TimeRange = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
}

/** This candle in the continuous series through [space], re-stamped [symbol]. */
internal fun Candle.inSpace(
    space: PriceSpace,
    symbol: String,
): Candle =
    copy(
        symbol = symbol,
        open = space.toContinuous(open),
        high = space.toContinuous(high),
        low = space.toContinuous(low),
        close = space.toContinuous(close),
        bid = bid?.let(space::toContinuous),
        ask = ask?.let(space::toContinuous),
    )

/** This tick in the continuous series through [space], re-stamped [symbol]. */
internal fun Tick.inSpace(
    space: PriceSpace,
    symbol: String,
): Tick =
    copy(
        symbol = symbol,
        price = space.toContinuous(price),
        bid = bid?.let(space::toContinuous),
        ask = ask?.let(space::toContinuous),
    )
