package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ChainSegment
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Serves continuous futures streams (`VENUE:ROOT@front`) from the per-contract data of [inner]: each
 * stretch of the stream reads the contract the roll schedule names, mapped onto the series by that
 * contract's forward adjustment and re-stamped with the continuous symbol. A stream is served from its
 * first measured roll on; earlier requests are clipped (a warmup reaching back sees fewer bars).
 * Explicit contracts end at their expiry with a settlement print ([DatedContractData]). Every other
 * symbol is served by [inner] unchanged, through the same calls and with the same per-symbol
 * capabilities.
 */
class ContinuousMarketSource(
    private val inner: MarketSource,
    private val chains: ContinuousChains,
    private val instruments: InstrumentRegistry,
) : MarketSource {
    private val log = LoggerFactory.getLogger(ContinuousMarketSource::class.java)
    private val clipped = HashSet<String>()
    private val dated = DatedContractData(instruments)

    override val name: String get() = inner.name
    override val capabilities: Set<MarketSourceCapability> get() = inner.capabilities

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        if (chains.isContinuous(symbol)) inner.capabilities else inner.capabilitiesFor(symbol)

    override fun supports(symbol: String): Boolean = chains.isContinuous(symbol) || inner.supports(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed = inner.liveTicks(symbols)

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val chain =
            chains.chainFor(symbol) ?: return dated.bars(symbol, window, range, inner.bars(symbol, window, range))
        requireBarsAvoidRolls(chain, window, range)
        return stitched(chain, range) { contract, seg ->
            inner.bars(contract, window, seg.range()).filter { it.startTime >= seg.fromMs && it.startTime < seg.toMs }
        }.flatMap { (space, candles) -> candles.map { it.inSpace(space, symbol) } }
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val chain =
            chains.chainFor(symbol) ?: return dated.ticks(symbol, range, inner.ticks(symbol, range))
        return stitched(chain, range) { contract, seg ->
            inner.ticks(contract, seg.range()).filter { it.timestamp >= seg.fromMs && it.timestamp < seg.toMs }
        }.flatMap { (space, ticks) -> ticks.map { it.inSpace(space, symbol) } }
    }

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        require(!chains.isContinuous(symbol)) {
            "tick-resolved fills (--tick-fills) are not supported for continuous futures streams ($symbol)"
        }
        return dated.cut(symbol, inner.tickSlice(symbol, fromMs, toMs))
    }

    /** Each segment's price mapping (resolved now, so gaps in the history fail at request time) and its data. */
    private fun <T> stitched(
        chain: ContinuousChain,
        range: TimeRange,
        read: (contract: String, segment: ChainSegment) -> Sequence<T>,
    ): Sequence<Pair<PriceSpace, Sequence<T>>> {
        val fromMs = range.from.toEpochMilli()
        if (fromMs < chain.servedFromMs && clipped.add(chain.symbol)) {
            log.warn(
                "{} is served from its first measured roll, {}; earlier data is not available",
                chain.symbol,
                Instant.ofEpochMilli(chain.servedFromMs),
            )
        }
        return chain
            .segments(fromMs, range.to.toEpochMilli())
            .map { seg -> Triple(seg, chain.spaceFor(seg.index), chain.contractSymbol(seg.index)) }
            .asSequence()
            .map { (seg, space, contract) -> space to read(contract, seg) }
    }

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
