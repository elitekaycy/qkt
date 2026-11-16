package com.qkt.connector.gateway

import com.qkt.common.net.PeriodicReconciler
import com.qkt.instrument.EventDirectory
import com.qkt.instrument.EventFamily
import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SlotSymbol
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * The event families of one gateway account, live: a family (`KALSHI:KXINXU`) is the gateway's `kind: event`
 * listing of one series, read when the family is first asked for (a slot, an event `CHAIN:` stream or a strategy
 * check at start) through [read], a filtered listing read. From then on every [pollMs] the family is polled for what
 * changed since the last read (`updated_since`), or read in full when the gateway no longer has those changes, off
 * the hot path: a lookup reads the last snapshot. Each listed contract is an [InstrumentMeta] with its [EventTerms],
 * and new and changed ones go to [heard] (the account's symbol map), so contracts listed after start are tradable and
 * a market the venue pauses reads its `trading` mode at once. A contract that leaves the listing stays known, so its
 * settlement still finds its terms. Answers only symbols carrying [prefix].
 */
internal class GatewayEventFamilies(
    private val prefix: String,
    private val read: (List<Pair<String, String>>) -> WireInstrumentPage,
    private val heard: (List<WireInstrument>) -> Unit,
    pollMs: Long,
) : InstrumentRegistry,
    EventDirectory,
    AutoCloseable {
    private class Family(
        val root: String,
        val spec: EventFamily,
        val listed: Map<String, WireInstrument>,
        val symbols: List<String>,
        val asOf: Long,
    )

    private val log = LoggerFactory.getLogger(GatewayEventFamilies::class.java)
    private val symbols = GatewaySymbols(prefix)
    private val families = ConcurrentHashMap<String, Family>()
    private val metas = ConcurrentHashMap<String, InstrumentMeta>()
    private val poller = PeriodicReconciler(pollMs, ::refresh)

    /** A listed contract's metadata, or a family's slot's ([EventFamily.metaFor] without terms). */
    override fun lookup(qktSymbol: String): InstrumentMeta? =
        metas[qktSymbol] ?: SlotSymbol.parse(qktSymbol)?.let { family(it.root)?.metaFor(qktSymbol, null) }

    override fun events(): EventDirectory = this

    override fun family(root: String): EventFamily? = tracked(root)?.spec

    override fun contracts(root: String): List<String> = tracked(root)?.symbols.orEmpty()

    override fun aliveAt(
        root: String,
        atMs: Long,
    ): List<String> =
        contracts(root).filter { symbol ->
            val terms = metas.getValue(symbol).derivative as EventTerms
            terms.openAtMs <= atMs && atMs < terms.expiryMs
        }

    /** Polls every family read so far for its changes; one the gateway cannot answer keeps its last snapshot. */
    fun refresh() {
        for (family in families.values) families[family.root] = refreshed(family)
    }

    override fun close() = poller.stop()

    private fun tracked(root: String): Family? {
        if (!root.startsWith(prefix)) return null
        families[root]?.let { return it }
        return synchronized(this) {
            families[root] ?: load(root)?.also {
                families[root] = it
                poller.start()
            }
        }
    }

    private fun load(root: String): Family? {
        val page = read(listOf("kind" to "event", "series" to root.removePrefix(prefix)))
        val valid = valid(root, page.instruments)
        val first = valid.firstOrNull()?.first ?: return null
        val spec =
            EventFamily(
                root,
                first.currency,
                BigDecimal(first.tickSize),
                BigDecimal(first.volumeStep),
                BigDecimal(first.volumeMin),
            )
        return build(root, spec, valid, page.asOf)
    }

    private fun refreshed(family: Family): Family =
        runCatching { changes(family) }.getOrElse { first ->
            log.warn("event family {}: change poll failed ({}); reading it in full", family.root, first.message)
            runCatching { load(family.root) }.getOrNull() ?: family
        }

    private fun changes(family: Family): Family {
        val series = family.root.removePrefix(prefix)
        val page = read(listOf("kind" to "event", "series" to series, "updated_since" to family.asOf.toString()))
        if (page.instruments.isEmpty() && page.removed.isEmpty()) {
            return Family(family.root, family.spec, family.listed, family.symbols, page.asOf)
        }
        val gone = page.removed.map { it.code }.toSet()
        val listed = family.listed - gone + page.instruments.associateBy { it.code }
        return build(family.root, family.spec, valid(family.root, listed.values), page.asOf)
    }

    /** [instruments] with their metadata; a contract whose listing qkt cannot read is skipped with a warning. */
    private fun valid(
        root: String,
        instruments: Collection<WireInstrument>,
    ): List<Pair<WireInstrument, InstrumentMeta>> =
        instruments.mapNotNull { instrument ->
            runCatching { instrument to instrument.eventSpec(symbols.qkt(instrument.code)).meta }
                .onFailure { log.warn("event family {}: skipping {}", root, it.message) }
                .getOrNull()
        }

    private fun build(
        root: String,
        spec: EventFamily,
        valid: List<Pair<WireInstrument, InstrumentMeta>>,
        asOf: Long,
    ): Family {
        heard(valid.map { it.first })
        for ((_, meta) in valid) metas[meta.qktSymbol] = meta
        val ordered =
            valid.sortedWith(compareBy({ it.first.expiry }, { it.second.qktSymbol })).map { it.second.qktSymbol }
        val listed = valid.associate { it.first.code to it.first }
        return Family(root, spec, listed, ordered, asOf)
    }
}
