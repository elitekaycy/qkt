package com.qkt.marketdata.hub

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
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
) : MarketSource {
    override val name: String = "Hub"

    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(PREFIX)

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

/**
 * Where the hub store lives for this process.
 *
 * A deployment mounts the hub's root read-only and names it in the environment; a local run falls
 * back to a `hub/` directory beside the data root, so a checkout works with no configuration. The
 * engine only ever reads: the hub is the sole writer of its own store, and two writers would
 * interleave sequence numbers and corrupt the ordering every consumer depends on.
 */
fun hubRoot(dataRoot: Path): Path =
    System.getenv(HubMarketSource.ROOT_ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: dataRoot.resolve("hub")

/**
 * How much less than the hub a consumer is willing to believe.
 *
 * Defaults match the hub's own: no extra lag, derived records allowed. A production book that has
 * not yet audited a backfill sets `refuseDerived` and sees only what the hub actually observed.
 */
data class HubPolicy(
    val minLagMs: Long = 0L,
    val refuseDerived: Boolean = false,
) {
    init {
        require(minLagMs >= 0) { "HubPolicy.minLagMs must not be negative, got $minLagMs" }
    }
}

/**
 * The hidden per-field stream symbol a hub alias expands into: `HUB:<dataset>[.<scope>]/<field>`.
 *
 * Encoding the scope in the symbol rather than resolving it at evaluation time keeps the engine's
 * routing a pure string match, which is what lets a hub stream flow through the existing merge and
 * candle machinery without any of it knowing what a scope is.
 */
data class HubStreamSymbol(
    val dataset: String,
    val scope: String?,
    val field: String,
) {
    /** The routing symbol the engine matches on, e.g. `HUB:cal.high_impact.USD/surprise`. */
    val symbol: String = HubMarketSource.PREFIX + dataset + (if (scope != null) ".$scope" else "") + "/" + field

    companion object {
        fun parse(symbol: String): HubStreamSymbol {
            require(symbol.startsWith(HubMarketSource.PREFIX)) { "not a hub symbol: $symbol" }
            val body = symbol.removePrefix(HubMarketSource.PREFIX)
            val slash = body.indexOf('/')
            require(slash > 0 && slash < body.length - 1) {
                "hub symbol must be HUB:<dataset>[.<scope>]/<field>, got $symbol"
            }
            val field = body.substring(slash + 1)
            val head = body.substring(0, slash)
            val segments = head.split('.')
            // A dataset name is lowercase dotted; a scope is upper case (USD, ALL, EXNESS:XAUUSD).
            // Splitting on that rather than on position means a dataset may keep any number of
            // segments without the parser having to know how many.
            val last = segments.last()
            return if (segments.size > 2 && last == last.uppercase() && last.any { it.isLetter() }) {
                HubStreamSymbol(segments.dropLast(1).joinToString("."), last, field)
            } else {
                HubStreamSymbol(head, null, field)
            }
        }
    }
}
