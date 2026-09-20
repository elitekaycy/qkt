package com.qkt.marketdata.hub

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [MarketSource] for facts from a qkt-data-hub store, bound under the `HUB:` prefix.
 *
 * A hub dataset carries many fields per record, while the engine's universal datum carries one
 * price. Rather than widen [Tick] for one data source, a hub alias is expanded by the compiler
 * into one hidden stream per field, and this source serves each of those separately: the symbol
 * `HUB:cal.high_impact/surprise` yields that field's value stamped at the record's `known_at`.
 * All of a record's fields share that timestamp, so they arrive together and land on one bar.
 *
 * **The timestamp is `known_at`, never `effective_at`.** That single choice is what makes the
 * whole binding free of look-ahead: a strategy sees a fact at the instant the hub could have
 * known it, not at the instant the fact describes. A release scheduled for Friday is visible on
 * Monday as a schedule, while Friday's number is not visible until Friday.
 *
 * The engine reads; it never writes to the store and never fetches from a provider. The hub owns
 * acquisition, and mounting its root read-only is the deployment contract.
 */
class HubMarketSource(
    private val root: Path,
    private val policy: HubPolicy = HubPolicy(),
    private val staleAfterMs: Long = HubStoreConfig.DEFAULT_STALE_AFTER_MS,
) : MarketSource {
    override val name: String = "Hub"

    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS, MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(PREFIX)

    /**
     * Tail the store's journals for the requested field streams, through the same live feed
     * adaptor the MT5 poller uses. A bad reference fails HERE, at feed start, rather than
     * reading as undefined for the life of the session.
     *
     * The reconnect budget is effectively unbounded: a hub going stale is reported to the feed
     * as a disconnect and to strategies through `hub.health`, but it must never END the feed --
     * that would take the price ticks down with it.
     */
    override fun liveTicks(symbols: List<String>): TickFeed {
        val problems = validateHubStreams(root, symbols)
        require(problems.isEmpty()) { "hub data problems:\n  " + problems.joinToString("\n  ") }
        val fieldSymbols = symbols.filter { runCatching { HubStreamSymbol.parse(it) }.isSuccess }
        return LiveTickFeed(
            HubTailSource(root, fieldSymbols, policy, staleAfterMs = staleAfterMs),
            reconnectBudgetMs = Long.MAX_VALUE / 4,
        )
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val binding = HubStreamSymbol.parse(symbol)
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return records(binding, fromMs, toMs)
            .mapNotNull { record ->
                val value = record.fields[binding.field] ?: return@mapNotNull null
                Tick(symbol = symbol, price = value.scaledForEngine(), timestamp = visibleAt(record))
            }.asSequence()
    }

    /**
     * Bars for warmup seeding: one point-in-time observation becomes one flat candle, exactly as
     * the macro-series path already does, so the existing warmup coordinator needs no change.
     */
    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val binding = HubStreamSymbol.parse(symbol)
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return records(binding, fromMs, toMs)
            .mapNotNull { record ->
                val value = record.fields[binding.field]?.scaledForEngine() ?: return@mapNotNull null
                val at = visibleAt(record)
                Candle(
                    symbol = symbol,
                    open = value,
                    high = value,
                    low = value,
                    close = value,
                    volume = Money.ZERO,
                    startTime = at,
                    endTime = at + window.durationMs,
                )
            }.asSequence()
    }

    /**
     * The records of one dataset visible in a window, newest revision last.
     *
     * Reads the journal rather than a compiled snapshot: the journal is authoritative and always
     * present, while a snapshot is a derived convenience that may not have been compiled for the
     * requested window yet. Both hold the same records, so the choice affects speed, not results.
     */
    private fun records(
        binding: HubStreamSymbol,
        fromMs: Long,
        toMs: Long,
    ): List<HubRecord> {
        val journal = HubJournal(root, binding.dataset)
        return journal
            .readAll()
            .asSequence()
            .filter { it.scope == binding.scope || binding.scope == null }
            .filter { !policy.refuseDerived || !it.isDerived }
            .filter { visibleAt(it) in fromMs until toMs }
            .sortedWith(compareBy({ visibleAt(it) }, { it.scope }, { it.key }, { it.revision }, { it.seq }))
            .toList()
    }

    /**
     * When a consumer may act on a record: its `known_at`, plus any configured minimum lag.
     *
     * The lag exists so an operator can be deliberately more conservative than the hub was, for
     * instance while a new source's timestamps are still being trusted.
     */
    private fun visibleAt(record: HubRecord): Long = record.knownAt + policy.minLagMs

    /** Whether the store is reachable at all, so a deploy fails loudly rather than at first bar. */
    fun storeExists(): Boolean = Files.isDirectory(root.resolve("journal"))

    private fun BigDecimal.scaledForEngine(): BigDecimal = setScale(Money.SCALE, Money.ROUNDING)

    companion object {
        const val PREFIX: String = "HUB:"

        /** Environment variable naming the hub store root, for a deployment that mounts it. */
        const val ROOT_ENV: String = "QKT_HUB_ROOT"
    }
}
